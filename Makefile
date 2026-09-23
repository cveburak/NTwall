.PHONY: all setup build release install test lint clean run

GRADLEW := ./gradlew

all: build

setup:
	@$(GRADLEW) --version

build:
	@$(GRADLEW) assembleDebug

release:
	@$(GRADLEW) assembleRelease

install:
	@$(GRADLEW) installDebug

test:
	@$(GRADLEW) testDebugUnitTest

lint:
	@$(GRADLEW) lintRelease

clean:
	@$(GRADLEW) clean

run: build install
