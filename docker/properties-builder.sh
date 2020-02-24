#!/bin/bash

if [ "$SKIP_PROPERTIES_BUILDER" = true ]; then
  echo "Skipping properties builder"
  exit 0
fi

# mongo container provides the HOST/PORT
# api container provided DB Name, ID & PWD

if [ "$TEST_SCRIPT" != "" ]
then
        #for testing locally
        PROP_FILE=application.properties
else 
	PROP_FILE=/hygieia/config/application.properties
fi
  
#if [ "$MONGO_PORT" != "" ]; then
#	# Sample: MONGO_PORT=tcp://172.17.0.20:27017
#	MONGODB_HOST=`echo $MONGO_PORT|sed 's;.*://\([^:]*\):\(.*\);\1;'`
#	MONGODB_PORT=`echo $MONGO_PORT|sed 's;.*://\([^:]*\):\(.*\);\2;'`
#else
#	env
#	echo "ERROR: MONGO_PORT not defined"
#	exit 1
#fi

echo "MONGODB_HOST: $MONGODB_HOST"
echo "MONGODB_PORT: $MONGODB_PORT"



cat > $PROP_FILE <<EOF
#Database Name
dbname=${HYGIEIA_API_ENV_SPRING_DATA_MONGODB_DATABASE:-dashboarddb}

#Database HostName - default is localhost
dbhost=${MONGODB_HOST:-10.0.1.1}

#Database Port - default is 27017
dbport=${MONGODB_PORT:-27017}

#Database Username - default is blank
dbusername=${HYGIEIA_API_ENV_SPRING_DATA_MONGODB_USERNAME:-dashboarduser}

#Database Password - default is blank
dbpassword=${HYGIEIA_API_ENV_SPRING_DATA_MONGODB_PASSWORD:-dbpassword}

#Collector schedule (required)
gitlab.cron=${GITLAB_CRON:-0 0/5 * * * *}

# The page size
gitlab.pageSize=1000

# The folder depth - default is 10
gitlab.folderDepth=10

# If using username/token for API authentication
# (required for Cloudbees Jenkins Ops Center) For example,
gitlab.servers[0]=${GITLAB_PROTOCOL:-https}://${GITLAB_HOST:-gitlab.com}

# Another option: If using same username/password Jenkins auth,
# set username/apiKey to use HTTP Basic Auth (blank=no auth)
gitlab.usernames[0]=${GITLAB_USERNAME}

#Project ids
gitlab.projectIds=${GITLAB_PROJECT_IDS}

# A comma seperated list of api token corresponding to the project id mentioned above
gitlab.apiKeys=${GITLAB_API_TOKENS}

# Determines if build console log is collected - defaults to false
gitlab.saveLog=true

# Search criteria enabled via properties (max search criteria = 2)
gitlab.searchFields[0]= options.jobName
gitlab.searchFields[1]= niceName

# Timeout values
gitlab.connectTimeout=20000
gitlab.readTimeout=20000

EOF

echo "

===========================================
Properties file created `date`:  $PROP_FILE
Note: passwords hidden
===========================================
`cat $PROP_FILE |egrep -vi password`
 "

exit 0
