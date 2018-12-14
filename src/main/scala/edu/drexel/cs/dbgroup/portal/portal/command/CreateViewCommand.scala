package edu.drexel.cs.dbgroup.portal.portal.command;

import org.apache.spark.sql.catalyst.TableIdentifier
import edu.drexel.cs.dbgroup.portal.PortalContext;
import edu.drexel.cs.dbgroup.portal.portal.PortalParser;
import edu.drexel.cs.dbgroup.portal.portal.PortalShellConstants;

import org.apache.spark.sql.ModifierWorkaround

class CreateViewCommand(portalContext: PortalContext, portalQuery: String, tViewName: String,
  isMaterialized: Boolean) extends PortalCommand(portalContext) {

  //val schemaDescriptionFormat: String = "TView \'%s\' schema => %s";

  // begin primary constructor definition  
  if (portalQuery == null || portalQuery.isEmpty()) {
    throw new Exception(PortalShellConstants.InvalidQuerySyntax());
  };

  // begin method implementation  
  override def execute() = {
    try {
      queryExec = portalContext.executePortal(portalQuery);
      tempGraph = queryExec.toTGraph;

      //register a view with a name
      portalContext.sessionCatalog.createTempView(tViewName, queryExec.analyzed, false);

      if (isMaterialized) {
        tempGraph.materialize();
      }

      attributes += ("tViewName" -> tViewName)

    } catch {
      case ex: Exception => {
        //FIXME: handle exception correctly 
        throw new Exception(ex);
      };
    }
  };

  def getPlanDescription(): String = {
    if (queryExec == null) {
      throw new Exception(PortalShellConstants.InvalidExecutionPlan());
    };

    return queryExec.optimizedPlan.toString();
  };

  def getPortalQuery(): String = {
    return portalQuery;
  };

  // end method implementation 
}