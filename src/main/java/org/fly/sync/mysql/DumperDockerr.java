package org.fly.sync.mysql;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.istack.NotNull;
import io.reactivex.Observable;
import io.reactivex.ObservableEmitter;
import io.reactivex.ObservableOnSubscribe;
import io.reactivex.Scheduler;
import org.fly.sync.action.ChangePositionAction;
import org.fly.sync.action.InsertAction;
import org.fly.sync.contract.AbstractAction;
import org.fly.sync.contract.AbstractLifeCycle;
import org.fly.sync.contract.DbFactory;
import org.fly.sync.es.Es;
import org.fly.sync.exception.DumpFatalException;
import org.fly.sync.executor.Executor;
import org.fly.sync.executor.Statistic;
import org.fly.sync.mysql.model.Record;
import org.fly.sync.mysql.model.Records;
import org.fly.sync.mysql.parser.InsertParser;
import org.fly.sync.mysql.parser.PositionParser;
import org.fly.sync.setting.BinLog;
import org.fly.sync.setting.Config;
import org.fly.sync.setting.River;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

public class DumperDockerr extends AbstractLifeCycle implements DbFactory {

    private Config config;
    private River river;
    private DbFactory dbFactory;
    private BinLog.Position position = new BinLog.Position();
    private Process process;

    public final static Logger logger = LoggerFactory.getLogger(DumperDockerr.class);

    public DumperDockerr(@NotNull Config config, @NotNull River river, DbFactory dbFactory)
    {
        this.config = config;
        this.river = river;
        this.dbFactory = dbFactory;
    }

    @Override
    public void start() {
        super.start();
    }

    @Override
    public void stop() {
        super.stop();

        if (null != process && process.isAlive())
            process.destroy();
    }

    @Override
    public Es getEs() {
        return dbFactory.getEs();
    }

    @Override
    public MySql getMySql() {
        return dbFactory.getMySql();
    }

    @Override
    public River.Database getRiverDatabase() {
        return dbFactory.getRiverDatabase();
    }

    @Override
    public Statistic getStatistic() {
        return dbFactory.getStatistic();
    }

    @Override
    public ObjectMapper getJsonMapper() {
        return dbFactory.getJsonMapper();
    }

    public Observable<AbstractAction> run(Scheduler scheduler)
    {
        StringBuilder cmd = new StringBuilder();

        /*
         * /usr/bin/mysqldump --host=XXXX --port=3306 --user=root --password=x xxxxxxxxxxxx \
         * --master-data --single-transaction --skip-lock-tables --compact --skip-opt \
         * --quick --no-create-info --skip-extended-insert --set-gtid-purged=OFF --default-character-set=utf8 \
         * schemaName table1 table2 table3 table4
         */
        River.Database database = getRiverDatabase();
        cmd.append(" exec ").append(config.mysqldump)
            .append(" --host=")
            .append(river.my.host)
            .append(" --port=")
            .append(river.my.port)
            .append(" --user=")
            .append(river.my.user)
            .append(" --password=")
            .append(river.my.password)
            .append(" --default-character-set=")
            .append(river.charset)
            .append(" --master-data --single-transaction --skip-lock-tables --compact --skip-opt --quick --hex-blob --no-create-info --skip-extended-insert --set-gtid-purged=OFF ")
            .append(database.schemaName);


        for (Map.Entry<String, River.Table> tableEntry: database.tables.entrySet()
             ) {
            if (!tableEntry.getValue().sync.created)
                continue;

            cmd.append(" ");
            cmd.append(tableEntry.getKey());
        }
//        cmd.append("\\' ");

        logger.info(cmd.toString());



        try {
            ProcessBuilder p = new ProcessBuilder(Arrays.asList("docker", "exec", "my_mysql_8", "sh", "-c", cmd.toString()));
//            p.command("");
            process = p.start();
//            process = Runtime.getRuntime().exec(cmd.toString());

        } catch (IOException e)
        {
            return Observable.error(new DumpFatalException(e));
        }
        logger.info("Dump database [{}] from mysqldump.", database.schemaName);

        return Observable.merge(
                errorObservable(process)
                        .subscribeOn(scheduler)
                        .observeOn(scheduler),
                dataObservable(process)
                        .subscribeOn(scheduler)
                        .observeOn(scheduler)

                )
            .doOnError(
                    throwable -> {
                        position.reset();
                        process.destroy();
                        process = null;
                    }
            );
    }

    private Observable<AbstractAction> dataObservable(Process process)
    {
        return Observable.create(new DataEmitter(process));
    }

    private Observable<AbstractAction> errorObservable(Process process){

        return Observable.create(observableEmitter -> {
            String s;

            try (
                    BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(process.getErrorStream()))
            ) {
                while(Executor.isRunning() && isStart())
                {
                    s = bufferedReader.readLine();
                    if (s == null)
                        break;

                    if (s.contains("[Warning]"))
                        continue;

                    observableEmitter.onError(new DumpFatalException(s));
                }

                observableEmitter.onComplete();

            } catch (IOException e)
            {
                observableEmitter.onError(new DumpFatalException(e));

            }

        });
    }

    private synchronized void parsePosition(String sql) {
        BinLog.Position position = PositionParser.parse(sql);
        if (position != null)
            this.position.updateFrom(position);
    }

    private class DataEmitter implements ObservableOnSubscribe<AbstractAction>
    {
        private Process process;
        String lastTable = null;
        List<List<String>> insertData = new ArrayList<>();
        ObservableEmitter<AbstractAction> observableEmitter;

        DataEmitter(Process process) {
            this.process = process;
        }

        void addInsertData(String table, List<String> data)
        {
            if (!table.equalsIgnoreCase(lastTable) || insertData.size() >= config.bulkSize)
                emit();

            insertData.add(data);
            lastTable = table;
        }

        private void emit()
        {
            if (insertData.isEmpty())
            {
                lastTable = null;
                return;
            }

            Records records = getMySql().getUtcQuery().mixRecords(getRiverDatabase().schemaName, lastTable, insertData, true);
            if (records == null)
                logger.warn("Lost {} records.", insertData.size());
            else
            {
                for (Record record: records
                ) {
                    record.setInserted();
                    observableEmitter.onNext(InsertAction.create(record));
                }
            }

            lastTable = null;
            insertData.clear();
        }

        @Override
        public void subscribe(ObservableEmitter<AbstractAction> observableEmitter) throws Exception {
            this.observableEmitter = observableEmitter;

            String sql;

            try (
                    BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(process.getInputStream()))
            ){

                while(Executor.isRunning() && isStart())
                {
                    if (getStatistic().getDumpCount().get() - getStatistic().getRecordCount().get() > config.bulkSize * 5)
                    {
                        //logger.info("Dump {} and subscribe {}, sleep 0.1s", total, getRecordCount.get());
                        try {

                            Thread.sleep(100);
                        } catch (InterruptedException e)
                        {
                            break;
                        }
                        continue;
                    }

                    sql = bufferedReader.readLine();

                    if (sql == null)
                    {
                        emit();
                        break;
                    }

                    getStatistic().getDumpCount().incrementAndGet();

                    if (sql.startsWith("CHANGE MASTER TO MASTER_LOG_FILE"))
                    {
                        emit();
                        parsePosition(sql);
                    } else if (sql.startsWith("INSERT INTO"))
                    {
                        String tableName = InsertParser.parseTable(sql);
                        if (tableName != null && !tableName.isEmpty()) {
                            List<String> data = InsertParser.parseValue(sql);
                            if (data != null)
                                addInsertData(tableName, data);
                        }

                    } else {
                        emit();
                        logger.warn("Skip SQL {} ", sql);
                    }
                }

                emit();

                if (!position.isEmpty())
                    observableEmitter.onNext(ChangePositionAction.create(position));

                observableEmitter.onComplete();

            } catch (IOException e)
            {
                observableEmitter.onError(new DumpFatalException(e));

            }

            logger.info("Dump database: [{}] complete;", getRiverDatabase().schemaName);
        }
    }

}
