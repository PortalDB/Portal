import sys;
sys.path.insert(0, './driverUtils')
import os;
import re;
import traceback;
import models, connect;
import subprocess;
import configparser;
import xml.etree.ElementTree as ET
from peewee import *;
from subprocess import Popen, PIPE;

#database = None;
#dbconnect = None;
strats = []
numParts = []

def collect_time(output):
    ftime = -1
    op_list = ["Aggregation", "Selection", "PageRank", "Count", "GetSnapshot"] #append to this list for new opearations
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

#helper fumction for collect_args
def get_agg_type(sem):
    if sem == "universal":
        return 0;
    return 1;   
    
#helper function for collect_args
def create_op(oT, a1, a2, pS, nP, rW):
    op = models.Operation(
        #op_id = _ _ _ (autogenerated in db)
        opType = oT,
        arg1 = a1,
        arg2 = a2,
        partitionS = pS,
        numParts = nP,
        runWidth = rW )
    return op

def collect_args(query):
    line = query.split(" ");
    opDict = {} #dictionary of all operations in the query
    seqNum = 1
    opType = arg1 = arg2 = partS = numParts = runW = None
    addOp = False

    for i in range (0, len(line)):
        if line[i] == "--agg":
            opType = "Aggregate"
            arg1 = runW = int(line[i+1][1:][:-1])
            arg2 = get_agg_type(line[i+2]) 
            partS = numParts = None

            if (len(line) > i+3) and (line[i+3] == "-p"):
                partS = line[i+4]
                numParts = int(line[i+5])
            addOp = True
    
        if line[i] == "--select":
            opType = "Select"
            arg1 = int(line[i+1].replace("-", ""))
            arg2 = int(line[i+2].replace("-", ""))
            partS = runW = numParts = None

            if (len(line) > i+3) and (line[i+3] == "-p"):
                runW = 1 #FIXME: what is runW for selection??
                partS = line[i+4]
                numParts = int(line[i+5])
            addOp = True

        if line[i] == "--pagerank":
            opType = "PageRank"
            arg1 = int(line[i+1])
            arg2 = 1 #FIXME: what is arg2 for pr??
            partS = runW = numParts = None

            if (len(line) > i+2) and (line[i+2] == "-p"):
                runW = 1 #FIXME: what is runW for pr??
                partS = line[i+3]
                numParts = int(line[i+4])
            addOp = True

        if line[i] == "--count":
            opType = "Count"
            arg1 = arg2 = None # no args for count
            partS = runW = numParts = None            

            if (len(line) > i+1) and (line[i+1] == "-p"):
                runW = 1 #FIXME: what is runW for count?
                partS = line[i+2]
                numParts = int(line[i+3]) 
            addOp = True
        
        if line[i] == "--getsnapshot":
            opType = "GetSnapshot"
            arg1 = int(line[i+1])
            arg2 = partS = runW = numParts = None

            if (len(line) > i+2) and (line[i+2] == "-p"):
                runW = 1  #FIXME: what is runW for gs??
                partS = line[i+3]
                numParts = int(line[i+4])
            addOp = True

        #create new operation
        if addOp == True:
            newOp = create_op(opType, arg1, arg2, partS, numParts, runW)
            opDict.update({seqNum: newOp})
            seqNum += 1        
            addOp = False        

    #for a,b in opDict.iteritems():
    #    print a, "-", b.opID, b.opType, b.arg1, b.arg2, b.partitionS, b.numParts, b.runWidth
    return opDict

def genQueries(line, replaceIndex):
    #partitionS = ["CanonicalRandomVertexCut", "EdgePartition2D", "NaiveTemporal", "ConsecutiveTemporal", "HybridRandomTemporal", "Hybrid2DTemporal"]
    #numParts = [8,16,32]
    queries = []
    query = line[:]

    for part in numParts:
        for strat in strats:
            newStrat = "-r" #default if no partitioning, -r is replaced with empty string before returning
            
            if (strat != "None"):
                newStrat = "-p " + strat + " " +  str(part)

            query[replaceIndex] = newStrat
            newQuery = (" ".join(query)).replace(" -r", "")
            
            #prevent duplicates
            if not newQuery in queries: 
                queries.append(newQuery)

    return queries;

# repeats query for all partition strategies and all numParts when -r is passed to an operation
def genRepetitions(query):
    line = query.split(" ");
    queries = []
    gen = False;
    replaceIndex = -1;
    
    for i in range (0, len(line)):
        if line[i] == "--agg":
            if (len(line) > i+3) and (line[i+3] == "-r"):
                gen = True;
                replaceIndex = i+3;
    
        if line[i] == "--select":
            if (len(line) > i+3) and (line[i+3] == "-r"):
                gen = True;
                replaceIndex = i+3;

        if line[i] == "--pagerank":
            if (len(line) > i+2) and (line[i+2] == "-r"):
                gen = True;
                replaceIndex = i+2;

        if line[i] == "--count":
            if (len(line) > i+1) and (line[i+1] == "-r"):
                gen = True;
                replaceIndex = i+1;
 
        if line[i] == "--getsnapshot":
            if (len(line) > i+2) and (line[i+2] == "-r"):
                gen = True;
                replaceIndex = i+2;

        #add generated queries to the list of queries to run
        if(gen == True):
            newQ = genQueries(line, replaceIndex)
            queries.extend(newQ)
            gen = False

    if(len(queries) == 0):
        queries.append((" ".join(line)).replace(" -r", "")) 
    
    return queries

def run(configFile):
    global strats;
    global numParts;

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
    numParts.extend(parser['configs']['numParts'].split(","))

    #read queries
    c = parser['test_queries']['Queries'].split("\n")
    que = filter(None, c) #queries read from config file

    gtypeParam = " --type "
    dataParam = "--data "
    sparkSubmit = "$SPARK_HOME/bin/spark-submit --class "
    warm = ""
    envConf = localConf #set to local environment by default

    if env == "ec2":
        envConf = ec2Conf
    elif env == 'mesos':
        envConf = mesosConf    
    elif env == 'standalone':
        envConf = standConf
 
    #run with warm start
    if sType == 1:
        warm = " --warmstart"    
                
    #get git revision number and save build information
    p1 = Popen('cat ../../.git/refs/heads/master', stdout=subprocess.PIPE, stderr=subprocess.PIPE, shell=True)
    gitRev = p1.communicate()[0];

    #read queries from file 
    for ln in que:
        line = ln.split(" ");
        qname = line[0]
        query = " ".join(line[1:-1]) + " " + line[-1].strip("\n") + " "
        queries = genRepetitions(query)
            
        #get cluster information when running on mesos cluster    
        if env == "mesos":
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
 
        elif env == "standalone":
            #TODO: consume this info from http://master:8081
            numWorkers = 4
            numCores = 2
            ram = 8
            cConf = str(numWorkers) + "s_" + str(numCores) + "c_" + str(ram) + "g"

        for q in queries: 
            classArg = q + dataParam + data + gtypeParam + gType + warm
            querySaved = False;
            qRef = None;
            op_dict = id_dict = {}
            sparkCommand = sparkSubmit + mainc + " " + envConf + " " + classArg + " | tee -a log.out"           

 
            print "sparkCommand:", sparkCommand
            print "cluster Config:", cConf
            print "[STATUS]: running the spark-submit command against dataset and collect results..."
            
            for i in range (1, itr+1):
                p3 = Popen(sparkCommand, stdout=subprocess.PIPE, stderr=subprocess.PIPE, shell=True);
                pres = p3.communicate()
                output = pres[0]
                time_dict = collect_time(output);
                rTime = None
                print output                

                try:
                    rTime = time_dict[0] #get total runtime
                except KeyError:
                    print "ERROR: Query run did not return a final runtime. See result below:"
                    print pres[1]
                    print traceback.format_exc()
                    sys.exit(1)

                #only run this once for each query
                if querySaved == False:
                    qRef = dbconnect.persist_query() #persist to Query table
                    op_dict = collect_args(q);
                    id_dict = dbconnect.persist_ops(op_dict) #persist to Operation table
                    dbconnect.persist_query_ops(qRef, id_dict) #persist tp Query_Op_Map table            
                    querySaved = True        
 
                bRef = dbconnect.persist_buildRef(buildN, gitRev.strip("\n"))
                eRef = dbconnect.persist_exec(time_dict, qRef, gType, sType, cConf, rTime, i, bRef, dataset)
                dbconnect.persist_time_op(eRef, qRef, id_dict, time_dict) 
                print "[STATUS]: finished running iteration", i, "of current query..\n" 
    print "***  Done with executions." 

if __name__ == "__main__":
    database = models.BaseModel._meta.database
    dbconnect = connect.DBConnection(database)

    if(not len(sys.argv) > 1):
        print ("ERROR: you must pass in a temporal graph query config file to read from")
        exit();
    else:
       arg1 = sys.argv[1];
       run(arg1);
