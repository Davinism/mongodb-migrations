# mongodb-migrations

mongodb-migrations is a command-line tool for running database migrations on MongoDB.

# Features

mongodb-migrations includes a locking mechanism to make sure that no two migration processes are running at the same time. In addition, a SHA1 hash is generated for each migration to make sure that the same migrations do not get run over and over again.

# Usage

This project needs to first be compiled into a .jar file. sbt-assembly is a tool that can be used for this process.

Once compiled, the following command will run the migrations:

```
java -jar <compiled-project-name>.jar <config-file>.conf <directory-with-evolution-scripts>
```

The compiled project name can be set in the build.sbt file.
The .conf config file contains the necessary information for the MongoDB connection. The following 4 fields need to be set in the .conf file:

- mongodb.uri (String, the URI for the MongoDB connection)
- mongodb.evolution.enabled (Boolean, boolean value to indicate whether or not the evolutions are enabled)
- mongodb.evolution.mongoCmd (String, the command to open up the MongoDB client)
- mongodb.db (String, the name of the MongoDB database)

Each of these fields must be specified with an equals sign. An example is shown below:
```
mongodb.uri = "mongodb://localhost:27017/mongodb-migrations"
mongodb.evolution.enabled=true
mongodb.evolution.mongoCmd="mongo localhost:27017/mongodb-migrations"
mongodb.db = mongodb-migrations
```

The fields that need to be set are subject to change in the future, as we will likely include authentication.

The directory with the evolution scripts includes .js files that contain the MongoDB commands to be run. MongoDB commands are natively JavaScript, so all files in this directory will have a .js extension. Also, the file names themselves will be numerical values that indicate the order of the migration (ascending order). As an example, the migration in "1.js" will go first, and then "2.js", and so forth.

Again, these files include the actual native MongoDB commands that will be run for the migrations.

Once all these parameters have been specified, mongodb-migrations will then proceed to run the scripts in ascending order, reading in the MongoDB commands from the .js evolution files and running them through the MongoDB client command specified in the .conf file.