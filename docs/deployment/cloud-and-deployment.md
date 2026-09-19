# Deployment & Cloud Guide

This is the tutorial-grade guide for deploying `datacraft-lab` — the cloud decision, the cost
picture, single-host vs. multi-host topology, and step-by-step instructions. It assumes the
architecture described in [`docs/architecture.md`](../architecture.md).

---

## 1. Cloud decision — summary

The full vendor evaluation, cost estimates, ARM-architecture verification and decision tree live in
[`CLOUD-DEPLOYMENT-ANALYSIS.html`](../../CLOUD-DEPLOYMENT-ANALYSIS.html) (Chinese). It is kept as a
single source of truth so the numbers are not duplicated and cannot drift. The conclusion:

- **Cloudflare cannot host the compute.** Workers/Pages/Durable Objects are short-lived edge
  runtimes; this platform needs long-lived stateful processes (Airflow scheduler, Postgres) and real
  CPU/memory for Spark. Use Cloudflare for **DNS, TLS, Tunnel, Access** (all free) and optionally
  **R2** for Parquet output and database backups — R2 charges no egress.
- **Run the workload on one small VM.** Sizing: ~2–3 GB idle, ~4–5 GB while a Spark job runs, so
  4 GB is the practical floor.
- **Recommended, in order:** Oracle Cloud Always Free (Ampere A1, free, but capacity is scarce and
  the free allowance was silently halved in June 2026) → AWS Lightsail 4 GB in Tokyo (predictable
  fixed price, the long-term pick) → EC2 `t4g.medium` with a Savings Plan (cheaper, more elastic,
  more to manage).
- **Rejected:** MWAA (~US$350+/mo), EMR/Glue (unnecessary at this data size), Kubernetes/EKS
  (operationally disproportionate — Swarm already covers multi-host).
- **All images support arm64** (verified), so the cheaper ARM instances are safe to use.

The scripts and orchestration files are **cloud-agnostic** and run on any Linux host, so none of the
above is a lock-in.

---

## 2. Prerequisites — tools to install

This workspace already has **JDK 25** (`D:\Java`), **Maven 3.9.15** (`D:\Maven`), **Python 3.14**,
and **git**. To build and deploy the containers you additionally need:

| Tool | Why | Install |
| --- | --- | --- |
| **Docker Engine + Compose v2** | Build images, run Compose/Swarm. **Not currently installed.** | Docker Desktop (Win/macOS) / Docker Engine (Linux) |
| `make` *(optional)* | `make up`, `make verify`, … convenience targets | OS package manager |
| **AWS CLI** *(for AWS)* | Provision/manage the VM | `https://aws.amazon.com/cli/` |
| **cloudflared** *(for Cloudflare Tunnel)* | Expose the UI securely | `https://developers.cloudflare.com/cloudflare-one/` |

> The project targets **Java 25** bytecode, so every runtime (local, container, and Spark cluster)
> must be JDK 25. Prefer **25.0.3 or newer** — Spark 4.2.0 deprecates older Java 25 releases. The
> container images use `eclipse-temurin:25-jre` and `apache/spark:4.2.0-...-java25-...` accordingly.
> See the note in §6 about the local NIO-selector limitation on this Windows workstation.

Every deployment script checks for Docker first and prints install guidance if it is missing, so
nothing destructive happens before the toolchain is ready.

---

## 3. Single-host deployment (Docker Compose) — start here

Topology: **Postgres + Airflow (api-server, scheduler, dag-processor, triggerer) on the custom
datacraft Airflow image, plus the datacraft JVM API**, with Airflow on **LocalExecutor** (no broker
— the smallest footprint).

```text
            ┌─────────────────────── one Linux VM ───────────────────────┐
 Cloudflare │  ┌────────────┐   ┌──────────────────────────────────────┐ │
 Tunnel  ──▶│  │ airflow-   │   │ scheduler · dag-processor · triggerer │ │
 (UI)       │  │ apiserver  │   └──────────────────────────────────────┘ │
            │  └────────────┘   ┌────────────┐   ┌───────────────────┐    │
            │        │          │  postgres  │   │   datacraft-api   │    │
            │        └──────────┴────────────┘   └───────────────────┘    │
            │   spark-submit (local[*]) + java -jar run inside Airflow     │
            └──────────────────────────────────────────────────────────────┘
```

### Steps

```bash
# 0) Clone and enter the repo on the VM, then:
cp deploy/compose/.env.example deploy/compose/.env
# Edit deploy/compose/.env — set POSTGRES_PASSWORD, AIRFLOW_FERNET_KEY, AIRFLOW_API_SECRET_KEY,
# AIRFLOW_JWT_SECRET, AIRFLOW_ADMIN_PASSWORD. Generate separate secrets:
python -c "from cryptography.fernet import Fernet; print(Fernet.generate_key().decode())"  # FERNET
openssl rand -hex 32                                                                       # API secret

# 1) Build the images (compiles the jar once, then layers the jvm + airflow images)
bash deploy/scripts/build-images.sh

# 2) Start the stack (runs DB migration + admin user automatically via airflow-init)
bash deploy/scripts/compose-up.sh

# 3) Open the UI
#    Airflow:        http://<host>:8080   (login: admin / your AIRFLOW_ADMIN_PASSWORD)
#    datacraft-api:  http://<host>:8088/health

# 4) Trigger a DAG (UI: unpause + run, or via the CLI inside a container)
#    - datacraft_engine_jobs : smoke test (echo/noop)
#    - datacraft_spark_etl   : csv -> parquet -> row-count  (set input/output in the trigger config)
#    - datacraft_sftp_ingest : SFTP download -> convert -> count (needs the datacraft_sftp connection)

# 5) Tear down (add -v to also delete the metadata DB volume)
bash deploy/scripts/compose-down.sh
```

### Putting Cloudflare in front (optional but recommended)

```bash
# On the VM, after `cloudflared` login:
cloudflared tunnel create datacraft
cloudflared tunnel route dns datacraft airflow.yourdomain.com
# Map the tunnel to the local Airflow UI:
#   ingress:
#     - hostname: airflow.yourdomain.com
#       service: http://localhost:8080
#     - service: http_status:404
cloudflared tunnel run datacraft
```

Then enable **Cloudflare Access** on `airflow.yourdomain.com` for email-gated login. No inbound
ports need to be open on the VM — the tunnel dials out to Cloudflare.

---

## 4. Multi-host deployment (Docker Swarm) — when one host isn't enough

Move to Swarm when task concurrency outgrows one VM, you want HA, or you want dedicated Spark
capacity. Swarm adds **Redis** as the Celery broker and an **airflow-worker** service whose replicas
can scale with CeleryExecutor. The supplied local-volume stack pins stateful services and workers to
`DATACRAFT_DATA_NODE` so file handoffs and database restarts stay on the same host. This default is
not multi-host high availability. Configure shared data/log storage before spreading workers.

```bash
# On the manager node:
docker swarm init                               # then `docker swarm join` on worker nodes

# Images must live in a registry both nodes can pull from. In deploy/compose/.env set:
#   DATACRAFT_REGISTRY=registry.example.com:5000
#   DATACRAFT_TAG=latest
#   AIRFLOW_WORKER_REPLICAS=2
#   DATACRAFT_DATA_NODE=<exact persistent node hostname>
bash deploy/scripts/build-images.sh
# Tag + push (example):
docker tag datacraft/airflow:latest "$DATACRAFT_REGISTRY/datacraft-airflow:latest" && docker push "$_"
docker tag datacraft/jvm:latest     "$DATACRAFT_REGISTRY/datacraft-jvm:latest"     && docker push "$_"

# Deploy the stack and run one-time init:
bash deploy/scripts/swarm-deploy.sh
bash deploy/scripts/airflow-init.sh
```

Production hardening for Swarm: replace node-local volumes with shared file/object storage suitable
for concurrent readers/writers (for example NFS/EFS), use `docker secret` instead of `.env` for credentials, and run a real Spark
cluster (point `DATACRAFT_SPARK_MASTER` at it) instead of local Spark if jobs grow large.

> Kubernetes/EKS is intentionally **not** used — it is overkill at this scale. Swarm is the right
> "next step up" from a single host.

---

## 5. Running Spark jobs on a real cluster

The jar is built with Spark as a **provided** dependency, so it is launched with `spark-submit`,
which supplies Spark at runtime. The Airflow image bundles `pyspark` (providing `spark-submit` +
the Spark runtime) for **local** mode. For a real cluster, set `DATACRAFT_SPARK_MASTER`
(e.g. `spark://host:7077`, `k8s://…`, or `yarn`); `spark_task` passes the same master to both
`spark-submit` and the CLI so the session matches the cluster.

---

## 6. Notes & known limitations

- **Local JDK-25 on Windows**: `java.nio.channels.Selector.open()` fails on this machine's JDK 25.0.2, so the JDK
  HTTP-server tests and `serve-api` cannot run locally here. This is a JDK/OS issue, not a code
  defect. Use Linux/WSL with JDK 25.0.3+ for the full `./mvnw verify` gate, including HTTP and Spark.
- **Image tags**: `Dockerfile.airflow` pins `apache/airflow:3.3.1` and `Dockerfile.spark` pins
  `apache/spark:4.2.0-scala2.13-java25-python3-ubuntu`. Both tags were verified to exist, but
  re-check before building if you change versions.
- **Metadata database**: Postgres 18 (Airflow 3.3.1 supports 14-18). A future Postgres major
  bump needs a `pg_dump`/restore, not just an image tag change.
- **Postgres 18 volume layout**: fresh stacks mount `/var/lib/postgresql`; the image writes under
  `/var/lib/postgresql/18/docker`. Existing volumes from the old `/var/lib/postgresql/data` mapping
  require inspection and backup/restore before applying this change. Do not delete a volume or
  assume changing the mount migrates data; this repository change does not migrate deployed data.
- **Execution API**: set a nonempty independent `AIRFLOW_JWT_SECRET` in `.env`; all Airflow components
  share it and use `http://airflow-apiserver:8080/execution/`. This is separate from the UI secret
  `AIRFLOW_API_SECRET_KEY` and connection encryption key `AIRFLOW_FERNET_KEY`.
- **Initialization**: `airflow-bootstrap.sh` performs both metadata/FAB migrations and checks whether
  the admin user exists. Migration, lookup, or creation failures stop startup. Re-running preserves
  an existing password; changing `.env` does not rotate it. In Swarm run `airflow-init.sh` on the data
  host where the scheduler container runs.
- **Engine API**: it has no authentication. Compose exposes port 8088 only on `127.0.0.1`; Swarm keeps
  the service on its overlay network. Use an authenticated gateway for remote access.
- **Configuration gates**: CI runs `docker compose config` and `docker stack config` on every push, so
  syntax and interpolation errors are caught automatically. Still re-run it locally after editing
  them, since Airflow occasionally renames components/flags between minors.
- **Secrets**: `.env` is gitignored. Never commit real keys. For Swarm/production prefer
  `docker secret`.
