import os;
import sys;
import traceback;
import models;
import datetime;
import peewee;
from peewee import *;

class DBConnection():
    @classmethod
    def __init__(self, db):
        self.database = db;




    def find_queryid(self, id_dict):
        try:
            sqlQuery = "SELECT DISTINCT alias0.query_id FROM"
            #adding the from clause
            for num in range (0, len(id_dict)):
                #In the last value: avoid comma and add the extra WHERE clause
                if num < len(id_dict) - 1:
                    sqlQuery = sqlQuery + " query_op_map alias" + str(num) + " ,"
                else:
                    sqlQuery = sqlQuery + " query_op_map alias" + str(num) + " WHERE"


            #adding the where clause
            num = 0
            for seqN, oid in id_dict.iteritems():
                if num < len(id_dict) - 1:
                    sqlQuery = sqlQuery + " alias" + str(num) + ".op_id=" + str(oid) + " AND  alias" + str(num) + ".seqNum=" + str(seqN) + " AND"
                else:
                    sqlQuery = sqlQuery + " alias" + str(num) + ".op_id=" + str(oid) + " AND  alias" + str(num) + ".seqNum=" + str(seqN) + " "
                num = num + 1

            #adding the equality for join
            num = 0
            for num in range (0, len(id_dict)):
                if num < len(id_dict) - 1:
                    sqlQuery = sqlQuery + " AND alias" + str(num) + ".query_id =" + " alias" + str(num+1) + ".query_id"

            #adding ORDER BY clause to find the smallest query_id
            sqlQuery = sqlQuery +   " ORDER BY alias0.query_id"

            result = self.database.execute_sql(sqlQuery)
            queryIdList = list(result.fetchall())
            if queryIdList:
                return queryIdList[0][0]
            else:
                return None
        except Exception:
            print "Unknown exception while trying to check \'Query_op_map\' table:"
            print traceback.format_exc()
            return None

    def persist_query(self):
        try:
            query = models.Query(query_id=None);
            query.save();
            return query

        except Exception:
            print "Exception while trying to save to \'Query\' table:"
            print traceback.format_exc()
            sys.exit(1)

    #this function checks if an operation already exists in the table before persisting
    def check_op_exists(self, op):
        try:
            #check if an exact copy of this operation exists
            operation = models.Operation.get((models.Operation.opType == op.opType) &
                                                (models.Operation.arg1 == op.arg1) & 
                                                (models.Operation.arg2 == op.arg2) &
                                                (models.Operation.arg3 == op.arg3) &
                                                (models.Operation.arg4 == op.arg4) &
                                                (models.Operation.arg5 == op.arg5) &
                                                (models.Operation.partitionS == op.partitionS) &
                                                (models.Operation.runWidth == op.runWidth))
            return operation
        except DoesNotExist:
            return None

        except Exception:
            print "Unknown exception while trying to check \'Operation\' table:"
            print traceback.format_exc()
            sys.exit(1)

        return None

    def persist_ops(self, op_dict):
        try:
            id_dict = {}
            with self.database.transaction():
                for seqN, operation in op_dict.iteritems():
                    #check if operation already exists
                    oldOp = self.check_op_exists(operation)

                    if oldOp == None:
                        #persist to Operation table and generate a new op_id
                        operation.save()
                    else:
                        #print "Operation", operation, "already exists in the \'Operation\' table"
                        operation.op_id = oldOp.op_id

                    id_dict.update({seqN: operation.op_id})
            
            return id_dict
        
        except Exception:
            print "Unknown exception while trying to save to \'Operation\' table:"
            print traceback.format_exc()                    
            sys.exit(1)
        
        return None

    def persist_query_ops(self, qRef, id_dict):
        query_ops = []

        try:
            for seqN, oid in id_dict.iteritems():
                oRef = models.Operation.get(models.Operation.op_id == oid)
            
                newQ = models.Query_Op_Map(
                                query_id = qRef,
                                op_id = oRef,
                                seqNum = seqN)
                query_ops.append(newQ)

            with self.database.transaction():
                for q in query_ops:
                    #print "Query info:", q.query_id, q.op_id, q.seqNum
                    q.save(force_insert=True)

        except Exception:
            print "Exception while trying to save to \'Query_Op_Map\' table:"
            print traceback.format_exc()
            sys.exit(1)
                
    def persist_buildRef(self, buildN, revNum):
        try:
            #check if revisionRed already exists
            bld = models.Build.get((models.Build.build_num == buildN) &
                                    (models.Build.revisionRef == revNum))
            return bld
        except DoesNotExist:
            build = models.Build(
                            build_num = buildN,
                            revisionRef = revNum,
                            description = "This is build #" + str(buildN) + ". Go to king.cs.drexel.edu/mrb327/temporaldata/commit/" + revNum + " to see the commit.")
            build.save(force_insert=True)
            return build
        
        except Exception:
            print "Exception while trying to save to \'Build\' table"
            print traceback.format_exc()
            sys.exit(1)
        
        return None

    def persist_exec(self, time_dict, qRef, gType, sType, cConf, rTime, itrN, bRef, dSet):
        try:
            for seqN, time in time_dict.iteritems():
                exe = models.Execution(
                                #exec_id = _ _ _ (generated in db)
                                query_id = qRef,
                                graphType = gType,
                                startType = sType,
                                clusterConfig = cConf,
                                runTime = rTime,
                                started = datetime.datetime.now(),
                                iterationNum = itrN,
                                build_num = bRef,
                                dataset = dSet)
                exe.save()
                return exe

        except Exception:
            print "Exception while trying to save to \'Execution\' table"
            print traceback.format_exc()
            sys.exit(1)
    
        return None

    def persist_queryTables(self, op_dict):
        id_dict = self.persist_ops(op_dict) #persist to Operation table
        #check if id already exists
        queryId = self.find_query_id(id_dict)
        if queryId is None:
            qRef = self.persist_query() #persist to Query table
            self.persist_query_ops(qRef, id_dict) #persist to Query_Op_Map table
            return qRef
        else:
            qRef = models.Query.get((models.Query.query_id == queryId))
            return qRef


    def persist_time_op(self, eRef, qRef, id_dict, time_dict):
        try:
            for seqN, oid in id_dict.iteritems():
                oRef = models.Operation.get(models.Operation.op_id == oid)

                time_op = models.Time_Per_Op(
                                        exec_id = eRef,
                                        query_id = qRef,
                                        op_id = oRef,
                                        seqNum = seqN,
                                        runTime = time_dict[seqN])

                time_op.save(force_insert=True)

        except Exception:
                print "Exception while trying to save to \'Time_Per_Op\' table"
                print traceback.format_exc()
                sys.exit(1)
