# Makefile for the Flix JetBrains plugin.
#
# Always use the checked-in Gradle wrapper so IntelliJ-platform and Kotlin
# plugin versions match CI.
#
# Usage:
#   make          # build the Java project
#   make build    # run the Gradle build
#   make test     # run the Gradle test suite
#   make check    # run verification without assembling a distribution
#   make inspect  # preview the CX context plan
#   make bundle   # create an immutable CX context bundle
#   make clean    # remove generated output
#
GRADLE ?= ./gradlew
CX ?= cx
CLEAN_DIR ?= dist/flix-jetbrains-plugin-bundle

.PHONY: all build test check inspect bundle clean help
all: build

build: ## Build the plugin with the checked-in Gradle wrapper.
	$(GRADLE) build

test: ## Run all module tests.
	$(GRADLE) test

check: ## Run verification tasks without assembling a distribution.
	$(GRADLE) check

inspect: ## Preview the strict, explicitly-owned CX context plan.
	$(CX) inspect --config cx.toml --token-breakdown

bundle: ## Create an immutable CX context bundle.
	$(CX) bundle --config cx.toml

clean: ## Remove generated output files.
	$(GRADLE) clean
	rm -rf "$(CLEAN_DIR)"

help: ## Show available targets.
	@printf "Available targets:\n  build test check inspect bundle clean\n"
