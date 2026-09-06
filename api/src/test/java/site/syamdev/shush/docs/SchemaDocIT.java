package site.syamdev.shush.docs;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import site.syamdev.shush.support.AbstractIT;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Writes {@code docs/SCHEMA.md} from the live, migrated database on every {@code ./mvnw verify}.
 *
 * <p>Flyway's migrations are the source of truth, and reading five files to picture the schema is
 * a fair complaint. This answers it without weakening forward-only migrations: rather than
 * maintaining a schema file by hand — which can drift the moment someone forgets — the document
 * is generated from the database *after* every migration has run. It cannot be wrong about what
 * the migrations produced, because it is asking them.
 *
 * <p>It is a test rather than a separate tool so it runs automatically and so it can also assert
 * the result is sane. If the file changes, {@code git diff} shows it and you commit it like any
 * other build output.
 */
class SchemaDocIT extends AbstractIT {

    private static final Path OUTPUT = Path.of("..", "docs", "SCHEMA.md");

    /** Flyway's own bookkeeping table is machinery, not part of the domain. */
    private static final String FLYWAY_TABLE = "flyway_schema_history";

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void generatesTheSchemaDocument() throws IOException {
        List<String> tables = jdbc.queryForList("""
                select c.relname
                from pg_class c
                join pg_namespace n on n.oid = c.relnamespace
                where n.nspname = 'public' and c.relkind = 'r' and c.relname <> ?
                order by c.relname
                """, String.class, FLYWAY_TABLE);

        StringBuilder doc = new StringBuilder();
        writeHeader(doc, tables.size());
        writeMigrations(doc);

        doc.append("\n---\n\n## Tables\n");
        for (String table : tables) {
            writeTable(doc, table);
        }

        Files.createDirectories(OUTPUT.getParent());
        Files.writeString(OUTPUT, doc.toString());

        // A generator that silently produced an empty file would be worse than none.
        String written = Files.readString(OUTPUT);
        assertThat(tables)
                .as("every table the migrations create should be documented")
                .contains("users", "conversations", "messages", "conversation_participants",
                        "friend_requests", "friendships", "media_objects", "interests");
        assertThat(written).contains("client_msg_id", "last_seq", "read_cursor_seq");
        assertThat(written.lines().count()).isGreaterThan(100);
    }

    private void writeHeader(StringBuilder doc, int tableCount) {
        doc.append("""
                # Shush — database schema

                <!--
                  GENERATED FILE — do not edit by hand.

                  Written by SchemaDocIT on every `cd api && ./mvnw verify`, by reading a real
                  Postgres after every Flyway migration has run against it. Editing this file
                  changes nothing; edit a migration in api/src/main/resources/db/migration/ and
                  rebuild. Migrations are forward-only: never change one that has been applied,
                  add the next one.
                -->

                > **Generated — do not edit.** Change the schema by adding a migration under
                > `api/src/main/resources/db/migration/`, then run `cd api && ./mvnw verify`.

                """);
        doc.append("Generated %s from %d tables.\n\n"
                .formatted(ZonedDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE), tableCount));
    }

    private void writeMigrations(StringBuilder doc) {
        doc.append("## Migrations applied\n\n");
        doc.append("| Version | Description | Applied |\n| --- | --- | --- |\n");
        jdbc.queryForList("""
                select version, description, success
                from flyway_schema_history
                where version is not null
                order by installed_rank
                """).forEach(row -> doc.append("| `V%s` | %s | %s |\n".formatted(
                row.get("version"), row.get("description"),
                Boolean.TRUE.equals(row.get("success")) ? "yes" : "**FAILED**")));
    }

    private void writeTable(StringBuilder doc, String table) {
        doc.append("\n### `").append(table).append("`\n\n");

        doc.append("| Column | Type | Null | Default |\n| --- | --- | --- | --- |\n");
        jdbc.queryForList("""
                select a.attname                                as name,
                       format_type(a.atttypid, a.atttypmod)     as type,
                       a.attnotnull                             as not_null,
                       pg_get_expr(d.adbin, d.adrelid)          as default_expr
                from pg_class c
                join pg_namespace n on n.oid = c.relnamespace
                join pg_attribute a on a.attrelid = c.oid and a.attnum > 0 and not a.attisdropped
                left join pg_attrdef d on d.adrelid = c.oid and d.adnum = a.attnum
                where n.nspname = 'public' and c.relname = ?
                order by a.attnum
                """, table).forEach(row -> doc.append("| `%s` | `%s` | %s | %s |\n".formatted(
                row.get("name"), row.get("type"),
                Boolean.TRUE.equals(row.get("not_null")) ? "no" : "yes",
                row.get("default_expr") == null ? "—" : "`" + row.get("default_expr") + "`")));

        writeConstraints(doc, table);
        writeIndexes(doc, table);
    }

    private void writeConstraints(StringBuilder doc, String table) {
        Map<Character, String> labels = new LinkedHashMap<>();
        labels.put('p', "Primary key");
        labels.put('f', "Foreign keys");
        labels.put('u', "Unique");
        labels.put('c', "Checks");

        List<Map<String, Object>> constraints = jdbc.queryForList("""
                select con.conname                        as name,
                       pg_get_constraintdef(con.oid)      as definition,
                       con.contype                        as kind
                from pg_constraint con
                join pg_class rel on rel.oid = con.conrelid
                join pg_namespace n on n.oid = rel.relnamespace
                where n.nspname = 'public' and rel.relname = ?
                order by con.contype, con.conname
                """, table);

        labels.forEach((kind, label) -> {
            List<Map<String, Object>> matching = constraints.stream()
                    .filter(row -> kind.equals(((String) row.get("kind")).charAt(0)))
                    .toList();
            if (matching.isEmpty()) {
                return;
            }
            doc.append("\n**").append(label).append("**\n\n");
            matching.forEach(row ->
                    doc.append("- `%s` — `%s`\n".formatted(row.get("name"), row.get("definition"))));
        });
    }

    private void writeIndexes(StringBuilder doc, String table) {
        // Constraint-backed indexes are already listed above; repeating them adds noise.
        List<Map<String, Object>> indexes = jdbc.queryForList("""
                select i.indexname as name, i.indexdef as definition
                from pg_indexes i
                where i.schemaname = 'public'
                  and i.tablename = ?
                  and not exists (select 1 from pg_constraint c where c.conname = i.indexname)
                order by i.indexname
                """, table);
        if (indexes.isEmpty()) {
            return;
        }
        doc.append("\n**Indexes**\n\n");
        indexes.forEach(row ->
                doc.append("- `%s` — `%s`\n".formatted(row.get("name"), row.get("definition"))));
    }
}
