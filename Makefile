MAKEFLAGS += --no-print-directory

.PHONY: mvn-version mvn-get

# Version of the newest commit that changed the library: 0.0.0-<UTC commit time>-<12-digit hash>.
mvn-version: pom.xml $(shell find src/main/java -name '*.java')
	@TZ=UTC git log -1 '--date=format-local:%Y%m%d%H%M%S' --abbrev=12 '--pretty=tformat:0.0.0-%cd-%h' $^

# Maven command that fetches this library at that version.
mvn-get:
	@echo mvn dependency:get -Dartifact=$(shell sed -n 's:^  <groupId>\(.*\)</groupId>$$:\1:p' pom.xml):$(shell sed -n 's:^  <artifactId>\(.*\)</artifactId>$$:\1:p' pom.xml):$(shell $(MAKE) -s -f $(firstword $(MAKEFILE_LIST)) mvn-version)
