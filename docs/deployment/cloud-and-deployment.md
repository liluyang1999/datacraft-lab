# Deployment & Cloud Guide

This is the tutorial-grade guide for deploying `datacraft-lab` — the cloud decision, the cost
picture, single-host vs. multi-host topology, and step-by-step instructions. It assumes the
architecture described in [`docs/architecture.md`](../architecture.md).

---

## 1. Cloud decision: Cloudflare vs. AWS

**Short answer: host the workload on a single small AWS VM, and put Cloudflare's free tier in front
of it for DNS, TLS, and secure access.** They are complementary, not alternatives.

### Why not "Cloudflare only"

`datacraft-lab` runs **long-lived, stateful processes**: the Airflow scheduler, API server,
DAG processor, and triggerer, a PostgreSQL metadata database, and Spark jobs that need real CPU and
memory. Cloudflare's compute products — Workers, Pages, Durable Objects, and the newer Workers
Containers — are built for **short-lived, event-driven, edge** execution. They are excellent for
HTTP handlers and cron-style functions but are not designed to run an always-on Airflow scheduler
with an attached relational database and JVM/Spark batch jobs. So Cloudflare cannot be the *compute*
host for this platform.

What Cloudflare **is** ideal for (and all on the **free** plan):

| Cloudflare feature | Use here |
| --- | --- |
| DNS + proxy | Point `airflow.yourdomain.com` at the VM, hide its origin IP. |
| Universal SSL | Free TLS termination — no certbot to manage. |
| Cloudflare Tunnel (`cloudflared`) | Expose the Airflow UI **without opening any inbound port** or needing a public IP. |
| Cloudflare Access (Zero Trust) | Free SSO/email-gated auth in front of the Airflow UI (up to 50 users). |
| DDoS / WAF | Basic protection at the edge. |

### Why AWS for the compute

| Option | Verdict for a personal lab |
| --- | --- |
| **AWS Lightsail** (fixed-price VM) | **Recommended for simplicity** — predictable monthly price, one-click Ubuntu. |
| **AWS EC2** (`t4g` Graviton/ARM) | **Recommended for cost** — cheapest with a savings plan; flexible. |
| AWS MWAA (managed Airflow) | ❌ ~US$350+/mo minimum — wildly overkill/expensive for personal use. |
| AWS EMR (managed Spark) | ❌ Not needed; jobs run in local Spark on the VM at this scale. |
| AWS RDS (managed Postgres) | Optional; the Postgres container on the same VM is free and fine for a lab. |

The deployment is **cloud-agnostic**: the Compose/Swarm files and scripts run on *any* Linux host
(AWS, a Hetzner/DigitalOcean VPS, or a home server). AWS is the recommendation, not a lock-in.

### Sizing & cost (estimates — verify current prices)

Airflow 3 (LocalExecutor) + Postgres + local Spark realistically wants **~4 GB RAM**; 2 GB is tight
once a Spark job runs. Rough monthly estimates, us-east-1, early-2026 ballpark:

| Setup | Spec | Est. monthly |
| --- | --- | --- |
| Lightsail | 2 vCPU / 4 GB / 80 GB | ~US$24 |
| EC2 `t4g.medium` on-demand | 2 vCPU / 4 GB + 30 GB gp3 | ~US$26 |
| EC2 `t4g.medium` + 1-yr Savings Plan | same | ~US$17 |
| Cloudflare (DNS/Tunnel/Access) | Free plan | US$0 |
| **Single-host total** | | **~US$20–30** |
| Swarm (2× `t4g.medium`) | HA / scale-out | ~US$45–60 |

> Treat these as estimates, not quotes. Always confirm against the live AWS and Cloudflare pricing
> pages for your region before committing.

**Recommendation:** start on **one EC2 `t4g.medium` (or Lightsail 4 GB)** with the single-host
Compose stack, fronted by **Cloudflare Tunnel + Access**. Scale to Swarm only when one host is no
longer enough (see §4).

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
# AIRFLOW_ADMIN_PASSWORD. Generate secrets:
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
spread across nodes (CeleryExecutor). State services (Postgres, Redis) are pinned to the manager.

```bash
# On the manager node:
docker swarm init                               # then `docker swarm join` on worker nodes

# Images must live in a registry both nodes can pull from. In deploy/compose/.env set:
#   DATACRAFT_REGISTRY=registry.example.com:5000
#   DATACRAFT_TAG=latest
#   AIRFLOW_WORKER_REPLICAS=2
bash deploy/scripts/build-images.sh
# Tag + push (example):
docker tag datacraft/airflow:latest "$DATACRAFT_REGISTRY/datacraft-airflow:latest" && docker push "$_"
docker tag datacraft/jvm:latest     "$DATACRAFT_REGISTRY/datacraft-jvm:latest"     && docker push "$_"

# Deploy the stack and run one-time init:
bash deploy/scripts/swarm-deploy.sh
bash deploy/scripts/airflow-init.sh
```

Production hardening for Swarm: replace node-local volumes with networked storage (NFS / AWS EBS
multi-attach / EFS), use `docker secret` instead of `.env` for credentials, and run a real Spark
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

- **Local JDK-25 on Windows**: `java.nio.channels.Selector.open()` fails on this machine, so the JDK
  HTTP-server tests and `serve-api` cannot run locally here. This is a JDK/OS issue, not a code
  defect — it works on Linux (containers, CI). Build/test the rest with
  `mvn -B -ntp verify`; the API is validated in CI and inside containers.
- **Image tags**: `Dockerfile.airflow` pins `apache/airflow:3.3.1` and `Dockerfile.spark` pins
  `apache/spark:4.2.0-scala2.13-java25-python3-ubuntu`. Both tags were verified to exist, but
  re-check before building if you change versions.
- **Metadata database**: Postgres 18 (Airflow 3.3.1 supports 14-18). A future Postgres major
  bump needs a `pg_dump`/restore, not just an image tag change.
- **`docker compose config`**: CI validates both the Compose and the Swarm file on every push, so
  syntax and interpolation errors are caught automatically. Still re-run it locally after editing
  them, since Airflow occasionally renames components/flags between minors.
- **Secrets**: `.env` is gitignored. Never commit real keys. For Swarm/production prefer
  `docker secret`.
