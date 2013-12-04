This document describes the typical use of maven to compile MidoNet, run its
test suite and create the jar, debian and rpm packages. If you find some errors
and/or typos, think something is not well explained or covered, please send a
mail to dev@midokura.com or open a Jira issue with Component = "Build/Release"
at midobugs.atlassian.net


## Installation

You can install your distro's mvn package or install it manually by downloading
source files and/or binaries @ maven.apache.org/download.cgi MidoNet maven
build system requires version mvn 3.0.4 or later. You can check this with
`$ mvn -version`

The few first times you run mvn on a machine, it will download many plugins and
jars from the Internet, otherwise known as "artifacts" in mvn jargon. Do not be
afraid if it takes quite a while. These artifacts are stored in your home
directory under $HOME/.m2/repository. In rare cases it sometimes happens that
this directory gets corrupted. in which case the quickest fix is to delete the
repository directory altogether and redownload everything through a full build
cycle.


## Pom file overview

MidoNet is organized as a "multi-module" maven project with a one
parent-children level:

    $ find ./ -iname pom.xml
    ./netlink/pom.xml
    ./packets/pom.xml
    ./midonet-api/pom.xml
    ./midonet-client/pom.xml
    ./pom.xml
    ./odp/pom.xml
    ./midonet-jdk-bootstrap/pom.xml
    ./midolman/pom.xml
    ./midonet-util/pom.xml

At the root of the midonet repository a top lvl pom.xml file declares general
settings like version numbers and plugin configurations. It is the pom file
with which mvn should always be run. In mvn jargon it is known at the "parent"
pom file.

Every subproject has an individual "child" pom file which inherits properties
from its parent pom file. Subproject pom files will have further plugin
declarations, dependency declarations, and plugin settings which override the
parent.

As much as possible, settings and configuration should go in the top parent
pom file. This reduces duplicate code, greatly eases maintenance, avoids many
problems with dependencies, and improves the consistency of the build system.

When you want to run build and test tasks, you can either act on the whole
projects, or target one particular subproject. Because in MidoNet, the
subprojects have some cross dependencies and are in general tightly coupled, in
the latter case you need to be careful about your mvn commands syntax,
especially when you change code on several submodules at the same time (more on
that below).


## Goals, plugins and phases

The general idea is that when invoked, maven will load the pom file settings
and after making sure the syntax is correct, it will execute a "goal". In maven
goals are standardized and follow precise life-cycles which are simply sequence
of goals. When you specify a particular goal in your command line, maven will
sequentially execute all previous goals as well. For example goal "test" will
always be after goal "test-compile", itself after goal "compile", and so on.

Goals by themselves are nothing but standard names. The real behavior are
triggered by plugins. Each maven plugin defines a set of "phases". These phases
can be triggered directly from the command line. For example to execute phase
bar of plugin foo, you would run the command `$ mvn foo:bar`. The other way to
have plugin phases executed is to bind them to goals. For instance, the maven
compiler plugin binds a phase called "compile" to the goal "compile". You can
invokes specific phases bind to goals by running $ mvn a_goal:target_phase, but
this is generally not necessary and is recommended against.

There are no general rules about how plugins should bind or not bind their
phases to maven goals. Sometimes some plugin will be executed by default while
sometimes they need to be manually bound to goals. Manual binding is done in
the plugin "executions" section. You can find examples in MidoNet pom files.

When a project is made of subprojects and a goal is invoked on that project,
maven will make a list of subprojects whose ordering follows the subproject
cross dependencies (which needs to be declared in the pom files). It will then
take the first subproject and execute all the goals to run on this project, then
move to the next subproject and execute the same goals there, then move on
to the third subproject and so on. There is unfortunately no easy way to
fine-tune goals per subproject on a multi subprojects run.


## Directory structure and build products

All build products of a particular subproject are by convention put under the
subproject/target/ directory. Class files are in subproject/target/classes.
Class files for tests are in subproject/target/test-classes, and so on. Jar
files are found at the root of the target directory.

Source code would also by convention go into subproject/src/main/jvm_language or
subproject/src/test/jvm_language for test code.

All these standard paths can be configured.


## Usage

This section gives a list of typical mvn commands with a short description of
what they do. Unfortunately unless you know a bit about writing pom file and
"the maven way", mvn command lines can look very arcane. If it happens that you
have some trouble achieving some particular task with maven, it might be easier
to decompose your task into two mvn commands.

### Project-wide commands

- clean all build products

`$ mvn clean`: this will clean all subproject/target/ directories along with
content for every subproject in MidoNet. Use this when you want to start from a
clean state.

- compilation

`$ mvn compile`: compiles main src files only, in every subproject.

`$ mvn test-compile`: compiles main and test src files in every subproject. Does
not run the tests.

- running tests

`$ mvn test`: runs all tests. Compiles all main and test source files.

`$ mvn test -Dtest=SomeTestClass`: run only the tests in SomeTestClass,
compiling all modules. mvn will find in which subproject this class lives
without further hints. If the class does not exist, no tests are run. In that
case the mvn command is a success if compilation is a success. If this
class exists in several subprojects, they are all run.

You can also run a group of tests with `$ mvn test -Dtest=TestA,TestB,TestC`

- packaging

`$ mvn package`: creates local jar, running the tests. For subprojects which
defines debian packages, it will also creates these packages.

`$ mvn package -DskipTests`: same as above minus running the tests.

`$ mvn install -DskipTests`: same as package, but in addition copies the jars
and packages to your local $HOME/.m2/repository directory.

This command is useful after switching branch with git, to save some
compilation time and run further simple mvn commands. For instance you can start
by running `$ mvn clean install -DskipTests` which will prepare jars for all
subprojects in MidoNet for the current branch, then do your changes on the code
and then recompile or run tests for specific submodules afterwards.

### Distro packages

After running `$ mvn package`, debian packages for midolman and midonet-api can
be found in midolman/target and midonet-api/target respectively.

To create rpm packages you should run `$ mvn package -Drpm`. On ubuntu this
requires to install the rpm tools with `$ apt-get install rpm`. rpm packages
will be located in midolman/target/rpm/midolman/RPMS/amd64/ and
midonet-api/same/path

### Subproject-wide commands

It is possible to invoke maven on only a single subproject or group
of subprojects. To do this you can add a "-pl subproject" argument
to your maven command line ("pl" stands for project list):

`$ mvn test -pl packets`: runs tests for the packets submodule only, compiling
whatever is necessary in packets subproject only.

When you target a subproject with dependencies on other MidoNet subprojects, you
have three ways to make mvn find the required jar files:

- 1) pre-install everything

You can first produce all jar files locally with `$ mvn install -DskipTests` or
`$ mvn package -DskipTests` and then run your command scoped for the subproject
of interest: `$ mvn test -pl odp`. This works well if you do not run
`$ mvn clean` often and do not depend on recent code changes on other
subprojects. If you have code to recompile in another subproject, this will not
work (or rather it will ignore this code). After changing branch it is a good
idea to go through this step.

- 2) the -am option

The second solution is to add -am option along -pl. "am" stands for "also make".
With this option maven will resolves local dependencies and figure out what it
needs to compile. This assumes of course that the pom files are correctly
written with all cross dependencies explicitly declared.

So for instance `$ mvn clean compile -am -pl netlink` will clean and compile the
netlink subproject with all netlink dependencies (jdk-bootstrap, midonet-util
and packets).

Unfortunately in this case the build goal will be shared for all subprojects
that maven finds it needs. For instance running `$ mvn test -am -pl midolman`
will also run tests of odp, packets and midonet-util subprojects. You can avoid
this problem if you specify a single test or list of tests with -Dtest, in which
case only those tests would be run.

- 3) manual resolution

If you know exactly which projects should be recompiled the third solution is
to specify them directly on the command line with for example
`$ mvn package -pl projA,projB -DskipTests`

### Running tests by groups

Junit integration in maven-surefire allows to group tests together by test
classes and/or test methods and run them selectively with maven from the
command line.

To tag a class or a method, add the import
"org.junit.experimental.categories.Category" in your file and add the
annotations `@Category(ClassName.class)` for java and
`@Category(Array(classOf[ClassName]))` for scala to your class or method, where
ClassName is an existing class or interface in package scope or in the imports.
All test methods inside a Category tagged class becomes tagged. The group of all
tests associated to ClassName can then be run exclusively by setting surefire
"<configuration>" section with "<groups>path.to.ClassName</groups>" or excluded
from tests by setting "<excludedGroups>path.to.ClassName</excludedGroups>".

At the moment (2013/10/23), only one group of tests is defined:
org.midonet.midolman.SimulationTests. It is included in the tests by default.

### Running services from source

It is possible to run both midolman and midonet-api from sources without
installing the native distro packages. First be sure to compile your project
so that midolman and/or midonet-api classes are in their respective target/
directories. You also need the jar files of all the internal dependencies
installed in your local maven repository. You can then start midolman with
`$ mvn -pl midolman exec:exec`
and midonet-api with
`$ mvn -pl midonet-api jetty:run -Djetty.port=8080`

The configuration files are looked up in target/classes,
src/main/resources. Midonet-api also requires a web.xml files in
src/main/webapp/WEB-INF/.


## Misc

This section describes additional mvn commands and settings you might want to
know about.

### Scala console

You can start the scala repl with midonet classes and dependencies on the
classpath with

`$ mvn scala:console -pl subproject`

If you do not precise a -pl subproject option, the repl will be started and
started again for every subproject starting from the jdk-bootstrap.

Because of a bug in scala 2.9 repl and JLine, after exiting the console started
by maven, the terminal will be left in a half-broken state, not displaying any
input. To restore your terminal, enter "reset" and hit return.

### Midonet-api doc generation

You can generate html docs for the midonet-api subproject with the enunciate
plugin by activating the profil apiDoc and running the command
`$ mvn package -PapiDoc -DskipTests`. The html files will be generated in
midonet-api/target/docs

### Using zinc server for compilation

Without zinc, mvn will start a new compiler instance for every set of source
files in src/main/ and src/test/ in every subproject and immediately terminate
the instance. This is fairly inefficient and because of the scala sbt compiler
requirements and the default memory settings of mvn, it is likely that you will
see OutOfMemory exceptions in this scenario.

To increase the available memory when running maven, you can set the environment
variable MAVEN_OPTS like `export MAVEN_OPTS="-Xms256m -Xmx1024m"` which would
set the minimal and maximal values of the java heap size to 256 and 1024 MB
respectively.

It is rather recommended to use the zinc server which caches a single sbt
compiler instance in the background. The easiest way to set up zinc on your
environment is to download the zinc tar from typesafe git repo and add the zinc
bin folder to your $PATH. You can then start zinc with `$ zinc -start`, and shut
it down with `$ zinc -shutdown`. The maven sbt plugin will automatically detect
if zinc is running on your system.

When switching branch with git, it might be necessary to restart the zinc server
because it would otherwise remember source files which are not valid anymore.
The typical symptoms of this problem are the abscence of any compiled class file
in the target/classes subdirectories of the affected subprojects. The root
problem seems to be that the scala-maven-plugin does not clean the zinc cache
when running `$ mvn clean`, which is good (save state and save up compilation
work) and bad (messes up compilation when switching branches) at the same time.

### Scalastyle check

To run a style analysis of the scala code, you can use the command

$ mvn scalastyle:check -pl subproject

Scalastyle will use the xml config file found at the route of midonet to
analysis the scala source code in main/ and test/.

### Code coverage reports

Cobertura allows to run tests while doing a coverage analysis of the code. It is
currently not bound to any standard phase of the build system and to generate
reports you have to run

`$ mvn clean site`

This command will generate a maven html "site" including the reports of
different plugins. The only reports we are interested into are the cobertura
reports which can be found at subproject/target/site/cobertura/ in html format.
Note that to generate the reports cobertura will need to run the unit tests, but
it is not necessary to explicitly add "test" in the mvn command.

Cobertura is known to conflict with zinc, and generating test coverage reports
will certainly require to turn off zinc first.

It is possible to include or exclude tests from the coverage analysis by
activating test group profiles. At the moment (2013/10/23), functional tests of
simulation using actors can be skipped with the profile "withoutSim":

`$ mvn clean site -PwithoutSim`

### Dependency analysis

mvn dependency plugin can run analysis to show your direct and hidden exposures
to external libraries and jar files. You can ask for reports with commands
`$ mvn dependency:analyze` or `$ mvn dependency:tree` for a more graphical
output. Be warned that $ mvn dependency:analyze has some false negatives
where it can diagnose jars as not used although they are required by the code
to correctly execute.


## Pom files internals

### Dependency declaration

Declaring dependencies in the midonet maven build system tries to follow these
two simple rules:
- if a jar file is needed by only one subproject, it is declared directly in
this subproject pom file, with explicit version number, scope and exclusion if
needed.
- if a jar file is needed by more than one subproject, it is declared in the top
level pom file in the "dependencyManagement" section with version number.
It can then be loaded on demand by subprojects in their pom file.

These two rules allow centralizing jar version numbers in one place while
keeping dependency declarations as close as possible to subprojects.

Because of implicit transitive dependencies, it is possible that some jars
needed in midonet by different pieces of code ask for conflicting version
numbers. In that case an exclusion section needs to be specified at some point
to avoid compile time or runtime issues. To avoid complexity and confusion,
ideally these exclusion sections would be specified as late as possible relative
to the subprojects' cross-dependencies. Notice that some problems may go
undetected at compile time if a jar file has an API type signature identical
between different versions but with different semantics.

### Plugin declaration

The main 4 plugins used in MidoNet build system are:
- scala-maven-plugin: to compile source files using the sbt compiler
- maven-surefire-plugin: to run unit tests of all subprojects
- jdeb: for packaging midolman and midonet-api as debian packages
- rpm-maven-plugin: for packaging midolman and midonet-api as rpm packages

As much as possible, plugin configurations are declared in the top level pom. To
learn how a plugin configuration can be tweaked, the easiest way is to go to the
plugin homepage and look at the section describing goals.

To avoid loading plugins for all subprojects automatically, plugins used by
more than one subproject are configured in the "pluginManagement" section of the
top level pom. Only the subprojects explicitly listing some of these plugins
will inherit common settings from the top level pom. maven-surefire is the only
exception since we use it across all subprojects for unit testing. In addition,
unique plugin usages are set directly in the relevant subprojects pom files.
Most of these unique plugins are for packaging-related tasks. Some examples:
jetty-maven-plugin, maven-war-plugin, git-commit-id-plugin, maven-shade-plugin,
maven-dependency-plugin ...
