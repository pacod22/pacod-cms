# pacod-cms -- `make` lists the targets. docs/RUNNING.md is the guide: dev, stage and prod, first
# start, demo content, verifying.
#
#   make init            once per machine: .env with ENV and fresh secrets (see `make help`)
#   make up              start this machine's environment
#
# Settings come from three files, the later winning:
#   env/<ENV>.env   the environment's shape -- profile, Caddyfile, ports, paths (tracked, no secrets)
#   .env            this machine: ENV=dev|stage|prod, its hosts and its secrets (git-ignored)
#   .env-local      optional overrides (git-ignored)
# A command-line assignment beats all three: `make up HTTP_PORT=8100`. `HTTP_PORT=8100 make up`
# does not: an included file beats the environment.

SHELL := /bin/bash
.DEFAULT_GOAL := help
comma := ,

# ---- settings -------------------------------------------------------------------------------------
# .env first to learn ENV, then the preset, then .env again so this machine's values win.
-include .env
-include .env-local
# From .env or the command line only: a shell's own ENV (sh and ksh read a startup file from it) is
# not this one.
ifneq ($(filter environment default undefined,$(origin ENV)),)
ENV := dev
endif
ifeq ($(filter $(ENV),dev stage prod),)
$(error ENV is "$(ENV)"; it must be dev, stage or prod)
endif
include env/$(ENV).env
-include .env
-include .env-local

# The addresses browsers use, derived from the hosts unless .env names them: https and no port on
# stage and prod, http://<host>:$(HTTP_PORT) on dev. pacod-id registers them exactly.
ifeq ($(PUBLIC_SCHEME),https)
PUBLIC_PORT := $(HTTPS_PORT)
PORT_SUFFIX := $(if $(filter 443,$(HTTPS_PORT)),,:$(HTTPS_PORT))
else
PUBLIC_PORT := $(HTTP_PORT)
PORT_SUFFIX := $(if $(filter 80,$(HTTP_PORT)),,:$(HTTP_PORT))
endif
STUDIO_ORIGIN ?= $(PUBLIC_SCHEME)://$(STUDIO_HOST)$(PORT_SUFFIX)
ID_ISSUER ?= $(PUBLIC_SCHEME)://$(ID_HOST)$(PORT_SUFFIX)
# The website the site-scoped targets act on: SITE=<id> (dev and stage: demo). Without a site domain
# (production may leave it blank) name its host: SITE_HOST=www.example.org.
SITE_HOST ?= $(if $(and $(SITE),$(PACOD_SITE_DOMAIN)),$(SITE).$(PACOD_SITE_DOMAIN))
SITE_URL = $(PUBLIC_SCHEME)://$(SITE_HOST)$(PORT_SUFFIX)
# Where the probes connect. Change it when something else owns the loopback port -- an
# `ssh -L 8000:…` tunnel beats Docker's binding: make smoke SMOKE_HOST=$$(ipconfig getifaddr en0)
SMOKE_HOST ?= localhost
PACOD_ID_SRC ?= ../pacod-id
PACOD_ID_IMAGE ?= pacod-id:dev
PACOD_GW_SRC ?= ../pacod-gw
PACOD_GW_LITE_IMAGE ?= pacod-gw-lite:dev
PACOD_GW_MOCK_IMAGE ?= pacod-gw-mock:dev
JAVA_HOME ?= $(shell /usr/libexec/java_home -v 25 2>/dev/null || echo /Library/Java/JavaVirtualMachines/jdk-25.jdk/Contents/Home)
MVN := JAVA_HOME=$(JAVA_HOME) PATH=$(JAVA_HOME)/bin:$$PATH ./mvnw

# Every value goes to the scripts and to compose -- whose shell environment beats its env files, so
# the values compose sees are make's. Except the secrets, which make never needs: compose reads
# those from the files itself, as it parses them.
export
unexport ENV MONGO_ROOT_PASSWORD MONGO_PASSWORD PID_PG_SUPERUSER_PASSWORD PID_DB_PASSWORD PID_KEK \
	PID_TOKEN_PEPPER CMS_ID_STUDIO_SECRET CMS_ID_SERVICE_SECRET ID_ADMIN_PASSWORD PACOD_PREVIEW_SECRET \
	METRICS_VISITOR_SALT PACOD_GW_API_KEY PACOD_ID_PROVISIONER_TOKEN PID_SMTP_USERNAME PID_SMTP_PASSWORD
# The helpers below are make functions: exported, they would be expanded -- and fire -- on every recipe.
unexport confirm need STUDIO_CLI MONGO_ROOT MVN comma RESTART_STUDIO
PACOD_ENV := $(ENV)
COMPOSE := docker compose --env-file env/$(ENV).env --env-file .env $(if $(wildcard .env-local),--env-file .env-local)

# $(call confirm,what): refuses outside dev unless CONFIRM=<ENV> is on the command line.
confirm = $(if $(filter dev,$(ENV)),,$(if $(filter $(ENV),$(CONFIRM)),,$(error $(1) on $(ENV): repeat with CONFIRM=$(ENV))))
# $(call need,VAR,hint): refuses an empty setting.
need = $(if $($(1)),,$(error $(1) is not set$(if $(2),: $(2))))
# A studio run without a web server, bootstrap, pacod-id or jobs, against the stack's MongoDB.
STUDIO_CLI = $(COMPOSE) run --rm --no-deps -T studio --spring.main.web-application-type=none \
	--pacod.bootstrap.enabled=false --pacod.id.enabled=false --pacod.jobs.runner.enabled=false \
	--logging.level.root=WARN --logging.level.io.pacod.cms.studio.site=INFO
# $(call MONGO_ROOT,exec-flags,tool): mongosh or mongorestore inside the mongo container, as the
# root account compose gave it -- the passwords never pass through make.
MONGO_ROOT = $(COMPOSE) exec $(1) mongo sh -c '$(2) -u "$$MONGO_INITDB_ROOT_USERNAME" -p "$$MONGO_INITDB_ROOT_PASSWORD" --authenticationDatabase admin "$$@"' --

.PHONY: help
help:
	@awk 'BEGIN { FS = ":.*## " } \
		/^##@/ { printf "\n\033[1m%s\033[0m\n", substr($$0, 5) } \
		/^[a-zA-Z0-9%_-]+:.*## / { printf "  \033[36m%-21s\033[0m %s\n", $$1, $$2 }' $(MAKEFILE_LIST)
	@echo
	@echo "This machine: ENV=$(ENV)$(if $(wildcard .env),, (no .env yet: make init))   studio $(STUDIO_ORIGIN)"

##@ Start
.PHONY: init up down status
init: ## Write .env, or add the secrets it lacks. Stage/prod: ENV=… STUDIO_HOST=… ID_HOST=… SITE_DOMAIN=… EMAIL=…
	@./scripts/init-env.sh $(ENV) $(foreach v,STUDIO_HOST ID_HOST SITE_DOMAIN EMAIL,$(if $(filter command line,$(origin $(v))),$(v)=$($(v))))

up: check network images ## Check, build and start the stack, wait until it answers, print its addresses
	@echo "Starting ENV=$(ENV)"
	@# --remove-orphans: a renamed or removed service's old container would keep holding its
	@# published ports. Caddy needs no restart when the applications are recreated.
	$(COMPOSE) up -d --build --remove-orphans
	@$(MAKE) --no-print-directory wait
	@# Self-service organisations need the studio's provisioner in pacod-id, which can only be made
	@# once pacod-id answers. Made once; afterwards this only checks it still exists.
	@$(MAKE) --no-print-directory id-provisioner
	@$(MAKE) --no-print-directory urls

down: ## Stop the stack; the data stays
	@echo "Stopping ENV=$(ENV)"
	$(COMPOSE) down

status: ## The environment, its containers and its addresses
	@echo "ENV=$(ENV)  (env/$(ENV).env, .env$(if $(wildcard .env-local),$(comma) .env-local))"
	@$(COMPOSE) ps --format 'table {{.Service}}\t{{.Status}}\t{{.Ports}}'
	@$(MAKE) --no-print-directory urls

##@ Content
.PHONY: site-create seed reseed-pages reseed-pages-restore restore mongo
site-create: ## Create a website: SLUG=acme NAME="Acme News" HOSTS=acme.localhost [LANGUAGE= ORG=org_… TEMPLATE=minimal DEMO=1]
	@test -n "$(SLUG)" -a -n "$(HOSTS)" || { echo 'usage: make site-create SLUG=acme NAME="Acme News" HOSTS=acme.example.net[,www.acme.example.net] [LANGUAGE=en] [ORG=org_…] [TEMPLATE=minimal] [DEMO=1]'; exit 2; }
	$(STUDIO_CLI) --create-site --site-id="$(SLUG)" --site-name="$(or $(NAME),$(SLUG))" --hosts="$(HOSTS)" \
		--language="$(or $(LANGUAGE),en)" $(if $(ORG),--org-id="$(ORG)") $(if $(TEMPLATE),--template-set="$(TEMPLATE)") $(if $(DEMO),--demo)

seed: ## Give a website the demo content (SITE=<id>; dev/stage: demo) -- only one without articles
	$(call need,SITE,make seed SITE=<website id>)
	$(STUDIO_CLI) --seed-demo --site-id="$(SITE)"

reseed-pages: ## Rebuild a website's standing pages from the seed (SITE=<id>); reports unless APPLY=1
	$(call need,SITE,make reseed-pages SITE=<website id>)
	$(if $(APPLY),$(call confirm,dropping the website's pages))
	@./scripts/reseed-pages.sh $(if $(APPLY),--apply)

reseed-pages-restore: ## Put a reseed-pages backup back: SITE=<id> FILE=.data/backup/pages/<id>-page-<stamp>.json
	@test -n "$(FILE)" || { echo "usage: make reseed-pages-restore SITE=<id> FILE=.data/backup/pages/<id>-page-<stamp>.json"; \
		ls -1t .data/backup/pages/*.json 2>/dev/null | head -5 | sed 's/^/  /'; exit 2; }
	@./scripts/reseed-pages.sh --restore "$(FILE)"

# DUMP is a directory under MONGO_BACKUP_DIR -- the only host folder the mongo container sees, at
# /data/backup -- containing mongodump's <db>/ folder. FROM_DB renames a dump of another database
# into TO_DB (the control plane by default, site_<id> for a website). --drop drops only the
# collections the dump carries; EXCLUDE="siteVisitor crawlerVisit" leaves some out.
restore: ## Restore a mongodump: DUMP=<dir under MONGO_BACKUP_DIR> [FROM_DB= TO_DB= EXCLUDE=]; reports unless APPLY=1
	@test -n "$(DUMP)" || { echo "usage: make restore DUMP=<dir under $(MONGO_BACKUP_DIR)> [FROM_DB=<db>] [TO_DB=<db>] [EXCLUDE=\"siteVisitor crawlerVisit\"] [APPLY=1]"; exit 2; }
	@test -d "$(MONGO_BACKUP_DIR)/$(DUMP)" || { echo "no such dump: $(MONGO_BACKUP_DIR)/$(DUMP)"; exit 1; }
	$(if $(APPLY),$(call confirm,restoring over the database))
	@echo "$(if $(APPLY),APPLYING to,Dry run against) '$(or $(TO_DB),$(or $(MONGO_DATABASE),pacod_cms))'$(if $(APPLY),; the dump's collections are dropped first,; nothing is written -- APPLY=1 to execute)."
	$(call MONGO_ROOT,-T,mongorestore) --drop $(if $(APPLY),,--dryRun --verbose) \
		$(if $(FROM_DB),--nsFrom "$(FROM_DB).*" --nsTo "$(or $(TO_DB),$(or $(MONGO_DATABASE),pacod_cms)).*") \
		$(foreach c,$(EXCLUDE),--nsExclude "*.$(c)") "/data/backup/$(DUMP)"
	@$(if $(APPLY),echo "Restored. The site caches for a minute: make restart and then make smoke.",true)

mongo: ## mongosh on the control plane; SITE=<id> on the command line: that website's database
	$(call MONGO_ROOT,,mongosh) "$(if $(filter command line,$(origin SITE)),site_$(SITE),$(or $(MONGO_DATABASE),pacod_cms))"

##@ Verify
.PHONY: check smoke smoke-tls e2e e2e-signin e2e-portal
check: ## Is this machine ready for ENV? .env, hosts, ports, mail, certificate pair, compose (run by up)
	@./scripts/check.sh $(ENV)

smoke: ## Every main route through Caddy, by host: expected codes and redirects; fails on a mismatch
	@./scripts/smoke.sh

smoke-tls: ## Each host's certificate over HTTPS, and whether it verifies
	@./scripts/smoke.sh certs

e2e: e2e-signin e2e-portal ## Sign-in and the portal end to end (dev, stage: needs mailpit; writes accounts)

e2e-signin: e2e-allowed
	node scripts/e2e-signin.mjs "$(CURDIR)" "$(STUDIO_ORIGIN)" "$(ID_ISSUER)" "http://localhost:$(MAILPIT_PORT)" "http://localhost:$(MGMT_PORT)"

e2e-portal: e2e-allowed
	node scripts/e2e-portal.mjs "$(CURDIR)" "$(STUDIO_ORIGIN)" "$(ID_ISSUER)" "http://localhost:$(MAILPIT_PORT)" \
		"$(PACOD_SITE_DOMAIN)" "$(if $(filter https,$(PUBLIC_SCHEME)),http://localhost:$(WEB_PORT),http://localhost:$(HTTP_PORT))"

.PHONY: e2e-allowed
e2e-allowed:
	@test "$(ENV)" != prod || { echo "e2e creates accounts and reads their mail in mailpit: not in production"; exit 1; }
	@case ",$(COMPOSE_PROFILES)," in *,mailpit,*) ;; *) echo "e2e reads pacod-id's mail in mailpit, which this ENV does not run"; exit 1;; esac
	@test -z "$(PID_SMTP_HOST)" -o "$(PID_SMTP_HOST)" = mailpit || { echo "PID_SMTP_HOST=$(PID_SMTP_HOST): mail goes to a relay, not mailpit"; exit 1; }

##@ Operate
.PHONY: restart logs caddy-reload destroy
restart: ## Rebuild and recreate site and studio; MongoDB, pacod-id and Caddy keep running
	$(COMPOSE) up -d --build --remove-orphans --no-deps site studio
	@$(MAKE) --no-print-directory wait

logs: ## Follow every log; logs-<service> follows one (site, studio, id, caddy, mongo, …)
	$(COMPOSE) logs -f --tail 100

logs-%:
	$(COMPOSE) logs -f --tail 200 $*

shell-%:
	$(COMPOSE) exec $* sh

caddy-reload: ## Check the Caddyfile, then recreate Caddy: after a host, Caddyfile or certificate-pair change
	@# Validated in a one-off container first, so a broken file never takes the front door down;
	@# then recreated, because Caddy reads {$$VAR}s when it parses the file.
	$(COMPOSE) run --rm --no-deps -T caddy caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile
	$(COMPOSE) up -d --force-recreate --no-deps caddy

destroy: ## Stop and DELETE every volume: content, accounts, certificates. Outside dev: CONFIRM=<ENV>
	$(call confirm,deleting every volume)
	@echo "Deleting ENV=$(ENV)'s volumes: MongoDB$(if $(findstring /,$(MONGO_DATA_DIR)), (not $(MONGO_DATA_DIR): a host folder; delete it yourself)), pacod-id's accounts, Caddy's certificates"
	$(COMPOSE) down -v

##@ Build and test
.PHONY: build test verify it brand-check clean
build: ## Compile and package every module (no tests)
	$(MVN) clean install -Dmaven.test.skip

test: ## Unit tests
	$(MVN) clean test

verify: brand-check ## Brand check, then the full build with unit tests -- before every commit
	$(MVN) clean verify

it: ## Integration tests (*IT) against a MongoDB container -- needs Docker
	$(MVN) verify -Pit

brand-check: ## Refuse any file carrying a word from a private deny-list (.local/brand-tokens.txt; skipped without it)
	@./scripts/brand-check.sh

clean: ## Remove Maven's build output
	$(MVN) clean

##@ Helpers (run by init and up; by hand rarely)
.PHONY: network images id-provisioner dev-cert keystore wait urls restart-studio
network: ## Create the external Docker network, if missing
	@docker network inspect "$${DOCKER_NETWORK:-pacod-cms-net}" >/dev/null 2>&1 \
		|| docker network create "$${DOCKER_NETWORK:-pacod-cms-net}"

images: ## Build pacod-id's image (and the mock gateway's, dev/stage) from their sources, if missing
	@docker image inspect "$(PACOD_ID_IMAGE)" >/dev/null 2>&1 || { \
		test -f "$(PACOD_ID_SRC)/deploy/docker/pacod-id.Dockerfile" \
			|| { echo "no image $(PACOD_ID_IMAGE) and no pacod-id sources at PACOD_ID_SRC=$(PACOD_ID_SRC)"; exit 1; }; \
		echo "building $(PACOD_ID_IMAGE) from $(PACOD_ID_SRC) -- a Rust build, slow the first time"; \
		docker build -f "$(PACOD_ID_SRC)/deploy/docker/pacod-id.Dockerfile" --target pacod-id -t "$(PACOD_ID_IMAGE)" "$(PACOD_ID_SRC)"; }
	@case ",$(COMPOSE_PROFILES)," in *,dev-gateway,*) ;; *) exit 0;; esac; \
	for pair in "$(PACOD_GW_LITE_IMAGE):lite" "$(PACOD_GW_MOCK_IMAGE):mock"; do \
		image=$${pair%:*}; target=$${pair##*:}; \
		docker image inspect "$$image" >/dev/null 2>&1 && continue; \
		test -f "$(PACOD_GW_SRC)/deploy/docker/rust.Dockerfile" \
			|| { echo "no image $$image and no pacod-gw sources at PACOD_GW_SRC=$(PACOD_GW_SRC)"; exit 1; }; \
		echo "building $$image from $(PACOD_GW_SRC) -- a Rust build, slow the first time"; \
		docker build -f "$(PACOD_GW_SRC)/deploy/docker/rust.Dockerfile" --target "$$target" -t "$$image" "$(PACOD_GW_SRC)" || exit 1; \
	done

# $(MAKE) behind a variable: named directly, make would run this line -- node included -- even
# under `make -n`.
RESTART_STUDIO = $(MAKE) --no-print-directory restart-studio
id-provisioner: ## The studio's provisioner account in pacod-id (ROTATE=1: a new token); recreates the studio when it writes one
	@out=$$(node scripts/id-provisioner.mjs "$(CURDIR)" "$(ID_ISSUER)" "$(STUDIO_ORIGIN)" $(if $(ROTATE),rotate)) || exit 1; \
	if [ -n "$$out" ]; then echo "$$out"; $(RESTART_STUDIO); fi

dev-cert: ## A self-signed certificate pair, to try SSL_CERT_FILE/SSL_KEY_FILE locally (docker/caddy/tls/)
	@./scripts/generate-dev-cert.sh

keystore: ## A self-signed keystore for TLS inside the JVM (config/keystore.p12)
	JAVA_HOME=$(JAVA_HOME) ./scripts/generate-dev-keystore.sh

restart-studio:
	$(COMPOSE) up -d --no-deps studio
	@$(MAKE) --no-print-directory wait

# Until the studio answers through Caddy, and the website too where one is named: the studio
# creates the demo websites on a first start.
wait:
	@echo "waiting for $(STUDIO_ORIGIN)$(if $(SITE_HOST), and $(SITE_URL)) ..."
	@for i in $$(seq 1 120); do \
		if curl -fsS -o /dev/null $(if $(filter dev,$(ENV)),-k) --connect-to '$(STUDIO_HOST):$(PUBLIC_PORT):$(SMOKE_HOST):$(PUBLIC_PORT)' '$(STUDIO_ORIGIN)/mgmt/login' 2>/dev/null \
		   $(if $(SITE_HOST),&& curl -fsS -o /dev/null $(if $(filter dev,$(ENV)),-k) --connect-to '$(SITE_HOST):$(PUBLIC_PORT):$(SMOKE_HOST):$(PUBLIC_PORT)' '$(SITE_URL)/' 2>/dev/null); then \
			echo "up."; exit 0; \
		fi; \
		sleep 2; \
	done; \
	echo "no answer after four minutes: make logs-studio, make logs-caddy"; exit 1

urls:
	@echo
	@echo "  studio    $(STUDIO_ORIGIN)/   (sign in as $(or $(ID_ADMIN_EMAIL),admin@pacod-cms.test); its password is ID_ADMIN_PASSWORD in $(if $(shell grep -s '^ID_ADMIN_PASSWORD=' .env-local),.env-local,.env))"
	@echo "  sign-in   $(ID_ISSUER)/"
	@$(if $(SITE_HOST),echo "  website   $(SITE_URL)/",true)
	@$(if $(findstring mailpit,$(COMPOSE_PROFILES)),echo "  mail      http://localhost:$(MAILPIT_PORT)/$(if $(filter dev,$(ENV)),,   (from elsewhere: ssh -L $(MAILPIT_PORT):localhost:$(MAILPIT_PORT) <server>))",true)
	@echo "  direct    site :$(WEB_PORT), studio :$(MGMT_PORT), MongoDB :$(MONGO_PORT)$(if $(PUBLISH_IP), (on $(PUBLISH_IP) only))"
