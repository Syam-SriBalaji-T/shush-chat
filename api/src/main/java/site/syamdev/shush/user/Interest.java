package site.syamdev.shush.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "interests")
public class Interest {

    @Id
    private Short id;

    @Column(name = "slug", nullable = false, columnDefinition = "text")
    private String slug;

    @Column(name = "label", nullable = false, columnDefinition = "text")
    private String label;

    @Column(name = "popularity", nullable = false)
    private int popularity;

    protected Interest() {
    }

    public Short getId() {
        return id;
    }

    public String getSlug() {
        return slug;
    }

    public String getLabel() {
        return label;
    }

    public int getPopularity() {
        return popularity;
    }
}
