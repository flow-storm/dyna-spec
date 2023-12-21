.PHONY: lint

clean:
	clj -T:build clean

lint:
	clj-kondo --config .clj-kondo/config.edn --lint src

dyna-spec.jar:
	clj -T:build jar

install: dyna-spec.jar
	mvn install:install-file -Dfile=target/dyna-spec.jar -DpomFile=target/classes/META-INF/maven/com.github.flow-storm/dyna-spec/pom.xml

deploy:
	mvn deploy:deploy-file -Dfile=target/dyna-spec.jar -DrepositoryId=clojars -DpomFile=target/classes/META-INF/maven/com.github.flow-storm/dyna-spec/pom.xml -Durl=https://clojars.org/repo


