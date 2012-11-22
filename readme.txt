PASSOS para o BUILD:

* Alterar a versão do "build.properties" no core/src (IMPORTANTE: não colocar ".touch" na versão);
* Rodar em core/src "ant all";
* Rodar em core/package/liquibase-[SUA VERSÂO]/ "mvn install:install-file -Dfile=liquibase-[SUA VERSÃO].jar / 
                         -DgroupId=org.liquibase /
                         -DartifactId=liquibase-core /
                         -Dversion=[SUA VERSÃO].touch /
                         -Dpackaging=jar /
                         -DgeneratePom=true"
* Rodar em maven "mvn clean install"
* Faça os testes necessários;
* Commit;
* Fazer release NA MÃO dos jars do "core" e do "plugin" via nexus (upload):
** "core": preencher os GAV parameters de acordo com o pom;
** "plugin": via pom;
* Passar tag no CVS.
