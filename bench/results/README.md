# Shush harness results

Captured 2026-09-05T18:15:11Z by running the committed harness against a stack
brought up from a clean clone. These predate the platform split; the topology (3 replicas
behind nginx, one Postgres, one Redpanda, one Elasticsearch, one MinIO) is unchanged by it.

## Hardware and method

```
kernel        Linux 6.6.87.2-microsoft-standard-WSL2
cpu           8 logical cores, AMD Ryzen 5 5600H with Radeon Graphics
memory        9.7Gi total to the VM
java          openjdk version "21.0.12" 2026-07-21
docker        Docker version 29.8.0, build 88096ef005
topology      3 api replicas behind nginx (least_conn, no stickiness)
              postgres 16, redis 7, redpanda v25.3, elasticsearch 8, minio
load driver   same machine as the system under test
```

## How to reproduce

```bash
# platform stacks first (the platform repo): data, streaming, search, edge
docker compose --env-file .env -f compose.platform.yaml up -d --build

# nginx resolves upstreams once at startup, so restart it after the replicas are recreated
docker compose --env-file .env -f edge/compose.yaml restart nginx   # in the platform repo

cd bench && ./mvnw clean package && cd ..
java -jar bench/target/shush-bench.jar --mode=ordering --via=nginx \
     --conversations=50 --messages=200 --assert-multinode
java -jar bench/target/shush-bench.jar --mode=chaos --via=nginx \
     --kill-node=api-2 --at-second=15 --conversations=50 --messages=500
```

The harness needs `SHUSH_DEV_ENDPOINTS=true` so it can open conversations without going
through matching. It is off by default, so an ordinary run cannot be driven this way.

## Summary

| Mode | Messages | Sockets | Wall clock | End-to-end throughput | Invariants |
| ---- | -------- | ------- | ---------- | --------------------- | ---------- |
| ordering | 10,000 | 100 | 17.8-34.6s | 289-563 msg/s | all held, 3/3 runs |
| chaos | 25,000 | 100 | 58.3-83.7s | 299-429 msg/s | all held, 3/3 runs |

Chaos throughput is lower because that mode deliberately *paces* its sends over a 40 s
window so the kill lands mid-flight. It is an offered rate, not a ceiling.

These are laptop figures with the load generator on the same machine as the system under
test, so they measure the pair together and the spread between runs shows it. They are
evidence that the invariants hold, not a throughput claim.

## Raw output

- `ordering.txt` - three ordering runs
- `chaos.txt` - three chaos runs, each killing api-2 fifteen seconds in
