PASSOS para o BUILD:

### Atenção: 

Para o procedimento abaixo, **não** usar o ant instalado do apt-get.
Faça o download do zip e descompacte - utilize esse ant aí!
http://ant.apache.org/bindownload.cgi

* Alterar a versão do "build.properties" no core/src (IMPORTANTE: não colocar ".touch" na versão);
* Rodar em core/src 

```shell
ant all
```

* Rodar em core/package/liquibase-[SUA VERSÂO]/ 

```shell
	mvn install:install-file -Dfile=liquibase-[SUA VERSÃO].jar \
	                     -DgroupId=org.liquibase \
	                     -DartifactId=liquibase-core \
	                     -Dversion=[SUA VERSÃO].touch \
	                     -Dpackaging=jar \
	                     -DgeneratePom=true
```

* Rodar em maven 

```shell
	mvn clean install
```

* Faça os testes necessários;
* Commit;
* Fazer release NA MÃO dos jars do "core" e do "plugin" via nexus (upload):
** "core": preencher os GAV parameters de acordo com o pom;
** "plugin": via pom;
* Passar tag no CVS.
