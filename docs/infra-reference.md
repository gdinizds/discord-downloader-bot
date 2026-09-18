# Infra Reference — Consul / Vault (prod)

Valores e comandos reais da infra de produção (cluster `hotbct`). Isso vive fora
do `README.md` de propósito: muda com a infra, não com o código, e os comandos
de segredo abaixo usam variáveis de shell/placeholder, nunca o valor literal.

Este app segue o mesmo padrão já usado no `discord-event-gateway`: Consul KV
pra config não-sensível, Vault Agent Injector pra segredo.

## Consul KV

Path lido pela app: `config/discord-downloader-bot/data` (perfil padrão, sem
`SPRING_PROFILES_ACTIVE`). Formato YAML, chave `data`. Só entra aqui o que
**não** é segredo — hoje só o Kafka, já que banco e S3/Garage vão 100% pro
Vault (host, porta, endpoint, tudo).

```bash
consul kv put config/discord-downloader-bot/data - <<'EOF'
spring:
  kafka:
    bootstrap-servers: redpanda.redpanda.svc.cluster.local:9093
EOF
```

> `bootstrap-servers` usa a porta `9093` (Kafka API do Redpanda no cluster
> `hotbct`), não a `9092` do endereço antigo (`192.168.10.95:9092`, infra
> anterior via LXD/MicroK8s). Se surgir um segundo ambiente (staging), ative
> com `SPRING_PROFILES_ACTIVE=<perfil>` no `Deployment` e grave o overlay em
> `config/discord-downloader-bot,<perfil>/data`.

## Vault — segredos

Kubernetes Auth Method, role `discord-downloader-bot-role`, ServiceAccount
`discord-downloader-bot` (namespace `discord-downloader-bot`,
`k8s/serviceaccount.yaml`). Consumidos via **Vault Agent Injector** (sidecar)
— a app não fala com o Vault diretamente, só lê os arquivos que o Agent grava
em `/vault/secrets/`. Os templates que fazem essa tradução (secret →
propriedades Spring) estão em `k8s/deployment.yaml`, nas annotations
`vault.hashicorp.com/agent-inject-template-*`.

### `secret/discord-downloader-bot/db` → `/vault/secrets/db.yaml`

| Campo no Vault | Vira propriedade Spring |
|---|---|
| `host` + `port` + `dbname` | `spring.datasource.url` / `spring.flyway.url` (URL JDBC montada no template) |
| `username` | `spring.datasource.username` |
| `password` | `spring.datasource.password` |
| `flyway_username` | `spring.flyway.user` |
| `flyway_password` | `spring.flyway.password` |

```bash
sudo k8s kubectl exec -n vault vault-0 -- vault kv put secret/discord-downloader-bot/db \
  host=postgresql-cnpg-rw.default.svc.cluster.local \
  port=5432 \
  dbname=app \
  username=discord_downloader_bot \
  password="$DB_PASS" \
  flyway_username=discord_downloader_flyway \
  flyway_password="$DB_FLYWAY_PASS"
```

> Banco compartilhado `app` (CloudNativePG), schema próprio `downloader` — é
> o mesmo Postgres usado pelo `discord-event-gateway` (schema `gateway`), só
> muda o schema. Os roles/schema/grants em si (`CREATE ROLE`, `CREATE SCHEMA
> downloader`, `GRANT`) são provisionados direto no Postgres, fora deste
> repo — ver seção "Pendente" abaixo.

### `secret/discord-downloader-bot/s3` → `/vault/secrets/s3.yaml`

| Campo no Vault | Vira propriedade Spring |
|---|---|
| `endpoint` | `downloader.s3.endpoint` |
| `bucket` | `downloader.s3.bucket` |
| `region` | `downloader.s3.region` |
| `access_key_id` | `downloader.s3.access-key` |
| `secret_access_key` | `downloader.s3.secret-key` |

```bash
sudo k8s kubectl exec -n vault vault-0 -- vault kv put secret/discord-downloader-bot/s3 \
  endpoint=http://garage.garage.svc.cluster.local:3900 \
  bucket=discord-downloader-attachments \
  region=garage \
  access_key_id="<KEY-ID>" \
  secret_access_key="<SECRET-KEY>"
```

## Vault — role e policy

Assume o auth method `kubernetes` já habilitado no Vault (já é o caso no
cluster `hotbct`, usado pelo `discord-event-gateway`):

```bash
vault policy write discord-downloader-bot - <<'EOF'
path "secret/data/discord-downloader-bot/*" {
  capabilities = ["read"]
}
path "secret/metadata/discord-downloader-bot/*" {
  capabilities = ["read"]
}
EOF

vault write auth/kubernetes/role/discord-downloader-bot-role \
  bound_service_account_names=discord-downloader-bot \
  bound_service_account_namespaces=discord-downloader-bot \
  policies=discord-downloader-bot \
  ttl=1h
```

## Cookies do yt-dlp (fora do Vault)

`k8s/deployment.yaml` monta um Secret `ytdlp-cookies` (arquivo, não env var)
em `/app/cookies-secret` — mesmo padrão de segredo-como-arquivo, mas fora do
fluxo Vault porque é conteúdo de cookies exportados manualmente do navegador,
não credencial gerada. Esse Secret **não** é criado por este repo; precisa
existir no namespace `discord-downloader-bot` antes do primeiro deploy:

```bash
sudo k8s kubectl create secret generic ytdlp-cookies \
  -n discord-downloader-bot \
  --from-file=youtube.txt=<caminho-local>/youtube-cookies.txt \
  --from-file=instagram.txt=<caminho-local>/instagram-cookies.txt \
  --from-file=tiktok.txt=<caminho-local>/tiktok-cookies.txt
```

## O que ficou obsoleto

- `k8s/configmap.yaml` e `k8s/secret.yaml` (infra anterior, IPs fixos
  `192.168.10.x`) foram removidos — substituídos por Consul KV e Vault acima.
  O `k8s/secret.yaml` antigo nunca chegou a ser versionado (já estava no
  `.gitignore`), mas continha credenciais reais em texto-base64 no disco local
  — depois de migrar os valores pros comandos `vault kv put` acima, apague
  qualquer cópia local que ainda exista e considere as credenciais antigas
  (senha do banco, chaves do Garage) comprometidas o suficiente pra rotacionar
  se ainda estiverem em uso em algum lugar.

---

## Cluster hotbct — visão geral

Infraestrutura Kubernetes já provisionada (cluster `hotbct`). Referência geral
do cluster — não específico deste app (mesmo texto usado no
`discord-event-gateway`).

### 1. Cluster

- **Distribuição**: Canonical Kubernetes (snap `k8s`), 3 nós em HA (control-plane + etcd nos 3)
- **Nós**: `k8s-master`, `k8s-worker1`, `k8s-worker2` — rede privada `10.0.0.0/22`
- **Ingress Controller**: Traefik (DaemonSet, hostNetwork, portas 80/443 diretas)
- **Storage**: StorageClass `csi-rawfile-default` (local, não replicado entre nós)
- **Container registry**: `ghcr.io/hotbct/*` pras imagens do cluster; este app usa a conta
  pessoal `ghcr.io/gdinizds/*` (ver seção "GitOps — pendente" abaixo)

### 2. Serviços de dados já disponíveis (acesso interno via DNS do cluster)

| Serviço | Namespace | Endereço interno | Observação |
|---|---|---|---|
| PostgreSQL | `default` | `postgresql-cnpg-rw.default.svc.cluster.local:5432` | CloudNativePG, instância única, Postgres 18.6, DB `app` compartilhado entre apps via schema |
| Redpanda (Kafka API) | `redpanda` | `redpanda.redpanda.svc.cluster.local:9093` | 3 brokers, replication factor 3, TLS desabilitado (interno) |
| Garage (S3-compatible) | `garage` | `garage.garage.svc.cluster.local:3900` | 3 nós, replicação distribuída, API S3 padrão, `path-style-access=true` obrigatório |
| HashiCorp Vault | `vault` | `vault.vault.svc.cluster.local:8200` | Kubernetes Auth Method habilitado |
| Consul | `consul` | agente local via DaemonSet em cada nó | Injeção sob demanda via annotation |
| Prometheus/Grafana | `monitoring` | Grafana em `grafana.hotbct.com` | kube-prometheus-stack |

### 3. Convenção de imagens e tags

- Tags semver, formato `vX.Y.Z`.
- Flux (Image Automation Controller) observa o GHCR e atualiza o manifest sozinho quando uma
  tag semver nova é publicada — não é necessário `kubectl apply`/deploy manual.

### 4. GitOps — repositório `gdinizds/fleet-infra`

- Repositório dedicado aos manifests declarativos do cluster (Flux CD).
- Estrutura: `clusters/production/<app>/`.
- Cada app novo precisa de 4 peças:
  1. `Deployment` + `Service` + `Ingress` (podem partir dos manifests deste repo, em `k8s/`)
  2. `ImageRepository` (aponta pra `ghcr.io/gdinizds/discord-downloader-bot`)
  3. `ImagePolicy` (regra semver, ex.: `>=0.1.0`)
  4. `ImageUpdateAutomation` (commita a atualização de volta no `fleet-infra`)
- **Segredos nunca ficam no repositório em texto plano.** Sempre Vault (seção acima) ou
  `existingSecret` já criado fora do Git.

## GitOps — pendente para este app

O CI/CD já está pronto neste repo (`.github/workflows/`):
- `ci.yml` — roda `./gradlew test` em todo push/PR pra `main`.
- `release.yml` — em toda tag `v*.*.*`, builda a imagem e publica em
  `ghcr.io/gdinizds/discord-downloader-bot:<tag>` e `:latest`.

O que falta, e não é feito a partir deste repo (fica em `gdinizds/fleet-infra`,
`clusters/production/discord-downloader-bot/`):

1. **Visibilidade do pacote GHCR** — um pacote novo no GHCR nasce **privado**.
   Depois do primeiro `git tag v0.1.0 && git push --tags` (dispara o
   `release.yml` e cria o pacote), tornar público em Packages →
   `discord-downloader-bot` → Package settings (ou linkar ao repo e liberar
   acesso), senão o cluster não consegue puxar a imagem sem `imagePullSecrets`.
2. **`Deployment` + `Service`** — partir de `k8s/deployment.yaml` e
   `k8s/service.yaml` deste repo; trocar a tag fixa `:latest` por marcação do
   Flux Image Automation.
3. **`ImageRepository`** apontando pra `ghcr.io/gdinizds/discord-downloader-bot`.
4. **`ImagePolicy`** com regra semver (ex.: `>=0.1.0`).
5. **`ImageUpdateAutomation`** pra commitar a atualização de volta no `fleet-infra`.
6. **Provisionamento no Postgres** (fora do Git, direto no banco): criar o
   role `discord_downloader_bot` (+ `discord_downloader_flyway`), o schema
   `downloader` no banco `app`, e os `GRANT`s — mesmo padrão usado pro schema
   `gateway` do `discord-event-gateway`.
7. **`imagePullSecrets: ghcr-pull-secret`** e **`Secret: ytdlp-cookies`** —
   precisam existir no namespace `discord-downloader-bot` antes do primeiro
   deploy (ver seção "Cookies do yt-dlp" acima pro segundo).
8. **`Ingress`** — não necessário: este app não expõe API HTTP pública (só
   `/actuator/*`, consumido internamente).
