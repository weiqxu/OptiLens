package dev.optilens.starrocks;

import dev.optilens.Model;
import com.starrocks.catalog.*;
import com.starrocks.common.FeConstants;
import com.starrocks.qe.ConnectContext;
import com.starrocks.server.GlobalStateMgr;
import com.starrocks.sql.ast.UserIdentity;
import com.starrocks.sql.optimizer.*;
import com.starrocks.sql.optimizer.base.*;
import com.starrocks.sql.optimizer.task.TaskContext;
import com.starrocks.thrift.TStorageType;
import java.util.*;

/** Isolated, in-memory FE metadata. No storage service, journal, or query execution is started. */
final class NativeSession {
  final ConnectContext connection;
  final ColumnRefFactory columns=new ColumnRefFactory();
  final OptimizerContext optimizer;
  final Map<String,OlapTable> tables=new LinkedHashMap<>();
  NativeSession(List<Model.Table> schema) {
    FeConstants.runningUnitTest=true;
    // Unpruned schema-only scans do not establish that a real table is empty.
    FeConstants.enablePruneEmptyOutputScan=false;
    connection=new ConnectContext();connection.setThreadLocalInfo();connection.setCurrentUserIdentity(UserIdentity.ROOT);
    connection.setQueryId(UUID.randomUUID());connection.setDatabase("optilens");
    Database db=new Database(99000,"optilens");long id=100000;
    for(var spec:schema) {
      var cols=new ArrayList<Column>();
      for(var c:spec.columns())cols.add(new Column(c.name(),NativePlans.type(c.type(),null),!Boolean.FALSE.equals(c.nullable())));
      var distribution=new HashDistributionInfo(1,List.of(cols.get(0)));
      var table=new OlapTable(id++,spec.name(),cols,KeysType.DUP_KEYS,new SinglePartitionInfo(),distribution);
      long index=id++;table.setBaseIndexId(index);
      table.setIndexMeta(index,spec.name(),cols,0,0,(short)0,TStorageType.COLUMN,KeysType.DUP_KEYS);
      table.setTableProperty(new TableProperty(new HashMap<>()));
      table.addPartition(new Partition(id++,id++,spec.name(),new MaterializedIndex(index,MaterializedIndex.IndexState.NORMAL),distribution));
      db.registerTableUnlocked(table);tables.put(spec.name().toLowerCase(Locale.ROOT),table);
    }
    GlobalStateMgr.getCurrentState().getLocalMetastore().replayCreateDb(db);
    optimizer=OptimizerFactory.mockContext(connection,columns);
  }
  TaskContext task(OptExpression root) {
    NativePlans.derive(root);
    var task=new TaskContext(optimizer,new PhysicalPropertySet(),root.getOutputColumns().clone(),Double.MAX_VALUE);
    optimizer.setTaskContext(task);return task;
  }
}
