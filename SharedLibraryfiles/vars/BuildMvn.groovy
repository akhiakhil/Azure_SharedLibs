def call() {
   echo ("Build STARTED")
             sh "/usr/share/apache-maven-3.8.6/bin/mvn-v && mvn -B package --file pom.xml "
   echo ("Build Completed")
      }









































