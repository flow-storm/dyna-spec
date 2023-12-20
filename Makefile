.PHONY: lint

clean:
	clj -T:build clean

lint:
	clj-kondo --config .clj-kondo/config.edn --lint src

type-trek.jar:
	clj -T:build jar

install: type-trek.jar
	mvn install:install-file -Dfile=target/type-trek.jar -DpomFile=target/classes/META-INF/maven/com.github.flow-storm/type-trek/pom.xml

deploy:
	mvn deploy:deploy-file -Dfile=target/type-trek.jar -DrepositoryId=clojars -DpomFile=target/classes/META-INF/maven/com.github.flow-storm/type-trek/pom.xml -Durl=https://clojars.org/repo


