import sys;
import re;
import traceback;
import driverUtils.models
import driverUtils.connect
import subprocess;
import configparser;
from peewee import *;
from subprocess import Popen, PIPE;
import driverUtils.sendMail

#database = None;
#dbconnect = None;
strats = []

def collect_time(output):
    ftime = -1
    op_list = ["Load", "Aggregation", "PageRank", "Materialize", "Union", "Intersection", "Return", "ConnectedComponents", "Slice", "Subgraph", "Project"] #append to this list for new opearations
    time_dict = {}
    
    #collect final runtime
    r = re.compile('Final Runtime: (.*?)ms\n')
    m = r.search(output)

    if m:
        ftime = int(m.group(1))
        time_dict.update({0 : ftime})

    #collect times for each operation
    for op in op_list:
        searchStr = op + " Runtime: (.*?)\n";
        r = re.compile(searchStr)
        m = r.findall(output)

        if m:
            for val in m:
                res = val.split(" ")
                opTime = res[0].replace("ms", "") 
                seqNum = res[1].replace("(", "").replace(")", "") 
                time_dict.update({int(seqNum) : int(opTime)})

    return time_dict  

#helper function for collect_args
def create_op(oT, a1, a2, a3, a4, a5, pS, rW):
    #print "create_p", oT, a1, a2, a3, a4, a5, pS, rW, "\n"
    op = driverUtils.models.Operation(
        #op_id = _ _ _ (autogenerated in db)
        opType = oT,
        arg1 = a1,
        arg2 = a2,
        arg3 = a3,
        arg4 = a4,
        arg5 = a5,
        partitionS = pS,
        runWidth = rW )
    return op

def collect_args(query, strat, run):
    line = query.split(" ");
    opDict = {} #dictionary of all operations in the query
    seqNum = 1
    opType = arg1 = arg2 = arg3 = arg4 = arg5 = partS = runW = None
    addOp = False

    for i in range (0, len(line)):
        if line[i] == "select" or line[i] == "\(select":
            if line[i+2][1] == '"':
                opType = "Load"
                arg1 = re.sub(r'\W+', '', line[i+2])
                arg2 = arg3 = arg4 = arg5 = None
                runW = run
                partS = strat
                addOp = True

        if line[i] == "group":
            opType = "Aggregation"
            arg1 = line[i+2]
            arg2 = line[i+3]
            arg3 = line[i+5]
            arg4 = line[i+8].strip('\)') #if in a subquery
            if line[i+10] == "vgroupby":
                arg5 = line[i+11]
            else:
                arg5 = None
            runW = int(line[i+2])
            partS = None
            addOp = True
    
        if line[i] == "where":
            if "start" in line[i+1]:
                opType = "Slice"
                arg1 = line[i+1][6:]
                arg2 = line[i+3][4:].strip('\)') #if in a subquery remove extra chars
            else:
                opType = "Subgraph"
                arg1 = line[i+1]
		arg2 = None
            arg3 = arg4 = arg5 = None
            runW = None
            partS = None
            addOp = True

        if line[i] == "project":
            opType = "Project"
            arg1 = line[i+1]
            arg2 = line[i+2]
            arg3 = arg4 = arg5 = None
            runW = None
            partS = None
            addOp = True

        if line[i] == "pagerank":
            opType = "PageRank"
            arg1 = line[i+1]
            arg2 = line[i+2]
            arg3 = line[i+3]
            arg4 = line[i+4]
            arg5 = None
	    partS = runW = None
            addOp = True

        if line[i] == "components":
            opType = "ConnectedComponents"
            arg1 = arg2 = arg3 = arg4 = arg5 = partS = runW = None
            addOp = True

        if line[i] == "materialize":
            opType = "Materialize"
	    arg1 = arg2 = arg3 = arg4 = arg5 = partS = runW = None
            addOp = True
        
        if line[i] == "intersection":
            opType = "Intersection"
	    arg1 = arg2 = arg3 = arg4 = arg5 = partS = runW = None
            addOp = True

        if line[i] == "union":
            opType = "Union"
	    arg1 = arg2 = arg3 = arg4 = arg5 = partS = runW = None            
	    addOp = True

        if line[i] == "return":
            opType = "Return"
            arg1 = line[i+1].split('.')[0]
            arg2 = line[i+1].split('.')[1]
            arg3 = arg4 = arg5 = partS = runW = None
            addOp = True

        #create new operation
        if addOp == True:
            newOp = create_op(opType, arg1, arg2, arg3, arg4, arg5, partS, runW)
            opDict.update({seqNum: newOp})
            seqNum += 1        
            addOp = False        

    #for a,b in opDict.iteritems():
    #    print a, "-", b.opID, b.opType, b.arg1, b.arg2, b.partitionS, b.runWidth
    return opDict

def genQueries(line, replaceIndex):
    #partitionS = ["CanonicalRandomVertexCut", "EdgePartition2D", "NaiveTemporal", "ConsecutiveTemporal", "HybridRandomTemporal", "Hybrid2DTemporal"]
    queries = []
    query = line[:]

    for strat in strats:
        if (strat != "None"):
            newStrat = "-p " + strat

            query[replaceIndex] = newStrat
            newQuery = (" ".join(query)).replace(" -r", "")
            
            #prevent duplicates
            if not newQuery in queries: 
                queries.append(newQuery)
                
    return queries;

def run(configFile, email):
    global strats;

    parser = configparser.ConfigParser()
    parser.read(configFile)

    #load configurations from file
    mainc = parser['configs']['main']
    env = parser['configs']['env']
    mesosConf = parser['configs']['mesosConfig']
    localConf = parser['configs']['localConfig']
    ec2Conf = parser['configs']['ec2Config']
    standConf = parser['configs']['standaloneConfig']
    cConf = parser['configs']['clusterConfig']
    buildN = int(parser['configs']['buildNum'])
    sType = int(parser['configs']['warm'])
    itr = int(parser['configs']['iterations'])
    gType = parser['configs']['type']
    data = parser['configs']['data']
    dataset = parser['configs']['dataset']
    strats.extend(parser['configs']['strategy'].split(","))
    runw = parser['configs']['runWidth']

    #read queries
    c = parser['test_queries']['Queries'].split("\n")
    que = filter(None, c) #queries read from config file

    gtypeParam = " --type "
    dataParam = "--data "
    stratParam = " --strategy "
    widthParam = " --runWidth "
    queryParam = " --query "
    warm = ""
    sparkSubmit = "$SPARK_HOME/bin/spark-submit --class "
    envConf = localConf #set to local environment by default

    if env == "ec2":
        envConf = ec2Conf
    elif env == 'mesos':
        envConf = mesosConf    
    elif env == 'standalone':
        envConf = standConf

    #run with warm start
    if sType == 1:
        warm = " --warmStart"
 
    #get git revision number and save build information
    p1 = Popen('cat .git/refs/heads/newmodel', stdout=subprocess.PIPE, stderr=subprocess.PIPE, shell=True)
    gitRev = p1.communicate()[0];

    #read queries from file 
    for ln in que:
        line = ln.split(" ");
        qname = line[0]
        query = " ".join(line[1:-1]) + " " + line[-1].strip("\n") + " "
        #get cluster information when running on mesos cluster
        if env == "mesos":
            #run sbt assembly
            Popen('sbt assembly', stdout=subprocess.PIPE, stderr=subprocess.PIPE, shell=True)
            #get cluster config
            p2 = Popen('curl http://master:5050/slaves', stdout=subprocess.PIPE, stderr=subprocess.PIPE, shell=True)
            out = p2.communicate()[0];
            numWorkers = out.count("\"active\":true")
            numCores = 2 #default num cores
            ram = 8 #default ram
            cConf = None

            r = re.compile('"cpus":(.*?),').search(out)
            if r:
                numCores = int(r.group(1))
                
            #set cluster config
            #FIXME: find ram of slaves
            cConf = str(numWorkers) + "s_" + str(numCores) + "c_" + str(ram) + "g"
    
        #get cluster information when running on standalone cluster    
        elif env == "standalone":
            #run sbt assembly
            Popen('sbt assembly', stdout=subprocess.PIPE, stderr=subprocess.PIPE, shell=True)
            #get cluster config
            p2 = Popen('curl http://master:8081', stdout=subprocess.PIPE, stderr=subprocess.PIPE, shell=True)
            out = p2.communicate()[0];
    
            out = out.replace(' ', '') 
            out = out.replace('\s', '') 
            out = out.replace('\t', '') 
            out = out.replace('\r', '') 
            out = out.replace('\n', '') 

            totalWorkers = numWorkers = totalCores = numCores = 0;
            ram = 16; #fixme: get actual ram 
            cConf = None

            #collect slave info
            r = re.compile('Workers:</strong>(.*?)</li>')
            m = r.search(out)

            if m:
                totalWorkers = int(m.group(1))            
                numDead = out.count('<td>DEAD</td>')    
                numWorkers = totalWorkers - numDead

            #collect cores info
            r = re.compile('Cores:</strong>(.*?)Total')
            m = r.search(out)

            if m:  
                totalCores = int(m.group(1))
                numCores = totalCores / totalWorkers
                
            #set cluster config
            #FIXME: find ram of slaves
            cConf = str(numWorkers) + "s_" + str(numCores) + "c_" + str(ram) + "g"  

        for strat in strats: 
            classArg = dataParam + data + gtypeParam + gType + stratParam + strat + widthParam + runw + warm + queryParam + query
            querySaved = False;
            qRef = None;
            op_dict = {}
            id_dict = {}
            sparkCommand = sparkSubmit + mainc + " " + envConf + " " + classArg + "| tee -a log.out"

            print "sparkCommand:", sparkCommand
            print "cluster Config:", cConf
            print "[STATUS]: running the spark-submit command against dataset and collect results...\n"
            #continue

            for i in range (1, itr+1):
                #run the experiment twice if you don't get the runtime
                errorCount = 0
		success = False
                while ((not success) & (errorCount < 2)):
                    try:
                        p3 = Popen(sparkCommand, stdout=subprocess.PIPE, stderr=subprocess.PIPE, shell=True);
                        pres = p3.communicate()
                        output = pres[0]
                        time_dict = collect_time(output);
                        rTime = None
                        print output
                        rTime = time_dict[0] #get total runtime
			success = True
                    except KeyError:
                        errorCount = errorCount + 1;
                        if errorCount == 1:
                            print "ERROR: Query run did not return a final runtime. Trying one more time."
                            continue
                        else:
                            print "ERROR: Query run did not return a final runtime in the second try. See result below:"
                            print pres[1]
                            print traceback.format_exc()
                            msg = "Subject: Job Failed \nERROR! Query run did not return a final runtime in the second try"
                            sendMail.sendMail(email, msg)
                            sys.exit(1)

                #only run this once for each query
                if querySaved == False:
                    op_dict = collect_args(query, strat, runw)
                    qRef = dbconnect.persist_queryTables(op_dict)
                    querySaved = True


                bRef = dbconnect.persist_buildRef(buildN, gitRev.strip("\n"))
                eRef = dbconnect.persist_exec(time_dict, qRef, gType, 0, cConf, rTime, i, bRef, dataset)
                dbconnect.persist_time_op(eRef, qRef, id_dict, time_dict) 
                print "[STATUS]: finished running iteration", i, "of current query..\n" 

    #getting number of workers after the job
    numWorkers = findNumberOfWorkers(env)
    print "***  Done with executions. Total number of workers after the experiment=" + str(numWorkers)
    msg = "Subject: Job Complete \nTotal number of workers after the experiment=" + str(numWorkers)
    sendMail.sendMail(email, msg)

def findNumberOfWorkers(env):
    numWorkers = 0
    if env == "mesos":
        p2 = Popen('curl http://master:5050/slaves', stdout=subprocess.PIPE, stderr=subprocess.PIPE, shell=True)
        out = p2.communicate()[0];
        numWorkers = out.count("\"active\":true")
    elif env == "standalone":
        p2 = Popen('curl http://master:8081', stdout=subprocess.PIPE, stderr=subprocess.PIPE, shell=True)
        out = p2.communicate()[0];

        out = out.replace(' ', '')
        out = out.replace('\s', '')
        out = out.replace('\t', '')
        out = out.replace('\r', '')
        out = out.replace('\n', '')

        r = re.compile('Workers:</strong>(.*?)</li>')
        m = r.search(out)
        if m:
            totalWorkers = int(m.group(1))
            numDead = out.count('<td>DEAD</td>')
            numWorkers = totalWorkers - numDead
    return numWorkers


if __name__ == "__main__":
    email = ""
    configFile = ""
    if(not len(sys.argv) > 1):
        print ("ERROR: you must pass in a temporal graph query config file to read from")
        exit();
    elif(len(sys.argv) < 3):
        email = "vera.zaychik@gmail.com"
        print "Email not provided, using " + email
    else:
        email = sys.argv[2];

    configFile = sys.argv[1];
    try:
        database = driverUtils.models.BaseModel._meta.database
        dbconnect = driverUtils.connect.DBConnection(database)
    	run(configFile, email)
    except SystemExit:
	msg = "ERROR! sys.exit() was called. Look for the output of the program to see what caused the exception"
	sendMail.sendMail(email, msg)
    except Exception:
	msg = "ERROR! Unknown exception occured. Look for the output of the program to see what cause the exception"
	sendMail.sendMail(email, msg)
	
	
