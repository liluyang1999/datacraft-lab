# datacraft-lab — convenience targets. JVM build uses Maven; deployment uses the deploy/ scripts.
MVN ?= mvn

.PHONY: help build test verify format package images up down swarm dags clean

help: ## Show available targets
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) | awk 'BEGIN{FS=":.*?## "}{printf "  \033[36m%-14s\033[0m %s\n", $$1, $$2}'

build: ## Compile all modules
	$(MVN) -B -ntp compile

test: ## Run unit tests
	$(MVN) -B -ntp test

verify: ## Full gate: format check + checkstyle + tests + package
	$(MVN) -B -ntp verify

format: ## Apply Spotless formatting to all sources
	$(MVN) -B -ntp spotless:apply

package: ## Build the shaded CLI jar
	$(MVN) -B -ntp -pl datacraft-cli -am package -DskipTests

images: ## Build all container images
	bash deploy/scripts/build-images.sh

up: ## Start the single-host stack (Docker Compose)
	bash deploy/scripts/compose-up.sh

down: ## Stop the single-host stack
	bash deploy/scripts/compose-down.sh

swarm: ## Deploy the multi-host stack (Docker Swarm)
	bash deploy/scripts/swarm-deploy.sh

dags: ## Syntax-check the Airflow DAGs
	python -m py_compile orchestration/airflow/dags/*.py

clean: ## Remove build output
	$(MVN) -B -ntp clean
