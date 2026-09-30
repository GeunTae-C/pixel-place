package dev.cgt.benchmark;

import java.nio.file.*;
import java.sql.*;
import java.util.*;

/** CREATE 전 live 인스턴스/intent와 CREATE 후 선택/빈 catalog 검사. 모든 DDL/DML/batch 직전 같은 guard 적용 */
final class Phase18Fixtures {
    private Phase18Fixtures() { }
    static void credentials(BenchmarkEnvironment.Credentials credentials,Phase18Plan plan) {
        // default 3306이나 다른 Redis로 실제 connection을 만들기 전 exact injection 확인
        Phase18Plan.require(credentials.dbHost().equals("127.0.0.1")&&credentials.dbPort()==3307
                &&credentials.redisHost().equals("127.0.0.1")&&credentials.redisPort()==6379&&credentials.redisDatabase()==plan.ownership().redisIndex(),"exact service injection");
    }
    static void live(BenchmarkFixtures fixture,Phase18Environment environment,Phase18Plan plan) throws Exception {
        credentials(fixture.credentials,plan);
        environment.current(plan);fixture.requireOwnedStorage();
        try(var redis=fixture.redisFactory.getConnection()) {
            var server=redis.serverCommands().info("server");
            Phase18Plan.require(server.getProperty("run_id").equals(environment.redisRunId())
                    &&Long.parseLong(server.getProperty("process_id"))==environment.redisPid(),"live Redis instance identity");
        }
        try(var c=fixture.adminConnect("");var s=c.createStatement();var rows=s.executeQuery("SELECT @@server_uuid,@@port,@@datadir,@@sync_binlog,@@innodb_flush_log_at_trx_commit")) {
            Phase18Plan.require(rows.next()&&rows.getString(1).equals(environment.mysqlUuid())&&rows.getInt(2)==3307
                    &&rows.getInt(4)==1&&rows.getInt(5)==1&&Path.of(rows.getString(3)).normalize().equals(Path.of(environment.mysqlRoot(),"data")),"live DB identity/durability");
        }
    }
    static void guard(Connection c,BenchmarkFixtures fixture,Phase18Environment environment,Phase18Plan plan) throws Exception {
        live(fixture,environment,plan);BenchmarkGuards.requireCatalog(c,plan.ownership().catalog());
    }
    static void empty(Connection c,String catalog) throws Exception {
        BenchmarkGuards.requireCatalog(c,catalog);
        for(String relation:List.of("tables","routines","events")) {
            String column=relation.equals("tables")?"table_schema":relation.equals("routines")?"routine_schema":"event_schema";
            try(var s=c.prepareStatement("SELECT COUNT(*) FROM information_schema."+relation+" WHERE "+column+"=?")){
                s.setString(1,catalog);try(var rows=s.executeQuery()){Phase18Plan.require(rows.next()&&rows.getLong(1)==0,"empty owned catalog");}
            }
        }
    }
    static List<Long> prepare(BenchmarkFixtures fixture,Phase18Environment env,Phase18Plan plan,Path output) throws Exception {
        live(fixture,env,plan);String catalog=plan.ownership().catalog();var caze=plan.cases().getFirst();
        Phase18Plan.require(catalog.equals(BenchmarkGuards.catalog(caze.runId().replace('-','_'))),"run namespace");
        var ddl=BenchmarkGuards.schema(Files.readString(Path.of(plan.ownership().repo(),"pixel_place.sql")));
        BenchmarkJson.write(output.resolve("catalog-intent.json"),Map.of("catalog",catalog,"runId",caze.runId(),"mode",env.catalogMode(),"environmentIdentity",plan.ownership().dbInstance()));
        try(var admin=fixture.adminConnect("");var find=admin.prepareStatement("SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name=?")) {
            find.setString(1,catalog);long count;try(var rows=find.executeQuery()){rows.next();count=rows.getLong(1);}
            if(env.catalogMode().equals("create-new")){
                Phase18Plan.require(count==0,"new catalog absent");live(fixture,env,plan);
                try(var s=admin.createStatement()){s.executeUpdate("CREATE DATABASE `"+catalog+"` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");}
            }else Phase18Plan.require(count==1&&env.precreatedOwner().equals(caze.runId()),"precreated current run owner");
        }
        var ids=new ArrayList<Long>();
        try(var c=fixture.connect(false)) {
            empty(c,catalog);BenchmarkJson.write(output.resolve("catalog-empty.json"),Map.of("catalog",catalog,"empty",true));
            for(String sql:ddl){guard(c,fixture,env,plan);try(var s=c.createStatement()){s.execute(sql);}}
            fixture.fixtureFiles();c.setAutoCommit(false);
            try(var insert=c.prepareStatement("INSERT INTO users(kakao_user_id) VALUES (?)",Statement.RETURN_GENERATED_KEYS)) {
                for(int i=0;i<plan.counts(caze).users();i++){
                    insert.setLong(1,9_018_000_000_000L+i);insert.addBatch();
                    if((i+1)%1000==0||i+1==plan.counts(caze).users()){
                        guard(c,fixture,env,plan);insert.executeBatch();try(var keys=insert.getGeneratedKeys()){while(keys.next())ids.add(keys.getLong(1));}insert.clearBatch();
                    }
                }
                Phase18Plan.require(ids.size()==plan.counts(caze).users()&&new HashSet<>(ids).size()==ids.size(),"generated users");c.commit();
            }catch(Exception|Error problem){try{c.rollback();}catch(Exception secondary){problem.addSuppressed(secondary);}throw problem;}
        }
        return List.copyOf(ids);
    }
}
