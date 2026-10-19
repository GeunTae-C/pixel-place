package dev.cgt.benchmark;

import tools.jackson.databind.JsonNode;
import java.util.Set;

/** CAP v2 정책의 일정·거절·유한 예약. legacy phase의 의미와 한도는 별도 유지 */
final class Phase18Cap {
    static final long NANO=1_000_000_000L, MAX_TERMINALS=140_000, MAX_DELIVERIES=16_000_000;
    record Schedule(String kind,long activeUsers,long writePeriodSeconds,long readPeriodSeconds,
                    long issuedEpochMillis,long expiresEpochMillis) {
        static Schedule legacy(){return new Schedule("legacy",0,0,0,0,0);}
    }
    static boolean enabled(Phase18Plan p){return (p.schemaVersion()==3||p.schemaVersion()==4&&p.stage().equals("CAP-1"))&&p.phase().equals("CAP");}
    static boolean scheduled(Phase18Plan.Workload w){return Set.of("smoke-s2","users").contains(w.schedule().kind());}
    static long count(Phase18Plan.Workload w,String kind,String phase){
        if(phase.equals("initial-read"))return kind.equals("read")&&w.schedule().kind().equals("users")?w.wsConnections():0;
        if(phase.equals("bootstrap"))return kind.equals("write")&&w.bootstrap()?1:0;
        if(!scheduled(w))return Math.multiplyExact(kind.equals("write")?w.writeRate():w.readRate(),phase.equals("warmup")?w.warmupSeconds():w.measurementSeconds());
        if(phase.equals("warmup")&&(kind.equals("write")||w.schedule().kind().equals("smoke-s2")))return 0;
        if(w.schedule().kind().equals("smoke-s2"))return 4;
        long people=kind.equals("write")?w.schedule().activeUsers():w.wsConnections();
        long period=kind.equals("write")?w.schedule().writePeriodSeconds():w.schedule().readPeriodSeconds();
        if(people==0||period==0)return 0;
        // offset=floor(ordinal*periodNanos/people) < measurementNanos 의 정확한 정수 예정량
        long seconds=phase.equals("warmup")?w.warmupSeconds():w.measurementSeconds();
        return Math.floorDiv(Math.addExact(Math.multiplyExact(seconds,people),period-1),period);
    }
    static long initialSeconds(Phase18Plan.Workload w){return w.schedule().kind().equals("users")?(w.wsConnections()+99)/100:0;}
    static long readOrdinal(Phase18Plan.Workload w,String phase,long ordinal){return ordinal+(phase.equals("initial-read")?0:count(w,"read","initial-read"))+(phase.equals("measurement")?count(w,"read","warmup"):0);}
    /** CAP-3 viewport identity is independent of the global ordinal used for sampling. */
    static int[] readPixel(Phase18Plan.Workload w,String phase,long ordinal){
        long tile;
        if(w.schedule().kind().equals("users"))tile=Math.floorMod(Math.addExact(Math.floorMod(w.seed(),1024L),ordinal%w.wsConnections()),1024L);
        else tile=w.readPattern().equals("hot")?0:readOrdinal(w,phase,ordinal)%1024;
        return new int[]{(int)(tile%32)*256,(int)(tile/32)*256,0};
    }
    static long offset(Phase18Plan.Workload w,String kind,long ordinal,String phase){return phase.equals("initial-read")?Math.multiplyExact(ordinal,10_000_000L):offset(w,kind,ordinal);}
    static long late(Phase18Plan.Workload w,String kind,String phase){return phase.equals("initial-read")?10_000_000L:late(w,kind);}
    static long offset(Phase18Plan.Workload w,String kind,long ordinal){
        if(w.schedule().kind().equals("smoke-s2"))return (kind.equals("write")?new long[]{0,1,200,201}[Math.toIntExact(ordinal)]:new long[]{10,60,110,160}[Math.toIntExact(ordinal)])*NANO;
        if(w.schedule().kind().equals("users"))return Math.multiplyExact(ordinal,Math.multiplyExact(kind.equals("write")?w.schedule().writePeriodSeconds():w.schedule().readPeriodSeconds(),NANO))/(kind.equals("write")?w.schedule().activeUsers():w.wsConnections());
        return Phase18Plan.scheduledOffsetNanos(ordinal,kind.equals("write")?w.writeRate():w.readRate());
    }
    static long late(Phase18Plan.Workload w,String kind){
        if(w.schedule().kind().equals("smoke-s2"))return NANO;
        if(w.schedule().kind().equals("users"))return Math.multiplyExact(kind.equals("write")?w.schedule().writePeriodSeconds():w.schedule().readPeriodSeconds(),NANO)/(kind.equals("write")?w.schedule().activeUsers():w.wsConnections());
        return NANO/(kind.equals("write")?w.writeRate():w.readRate());
    }
    static long pool(Phase18Plan.Workload w){
        // 최종 검증/기록 2초 + 요청 10초 + 재사용181초 + 처리중128명. bootstrap은 별도
        return Math.min(Math.addExact(count(w,"write","warmup"),count(w,"write","measurement")),Math.addExact(Math.multiplyExact(w.writeRate(),193),128));
    }
    static boolean rejection(String kind,int code,JsonNode body){
        if(!kind.equals("write")||!body.isObject())return false;
        if(code==503)return body.size()==1&&body.path("message").isTextual()&&body.path("message").asText().equals("Pixel write is busy. Please retry later.");
        return code==429&&body.size()==2&&body.path("message").isTextual()&&body.path("message").asText().equals("Pixel write cooldown is active.")
                &&body.path("remainingMillis").isIntegralNumber()&&body.path("remainingMillis").canConvertToLong()&&body.path("remainingMillis").longValue()>=0;
    }
    static boolean rejection(Phase18Raw r){return r.httpStatus()!=null&& r.kind().equals("write")&&!r.scheduledPhase().equals("bootstrap")&&r.sendState().equals("sent")&&r.outcome().equals("REJECTED")
            &&r.eventSeq()==null&&r.tileVersion()==null&&r.completedNanos()!=null&&r.sentNanos()!=null&&r.completedNanos()>=r.sentNanos()
            &&((r.httpStatus()==503&&r.status().equals("server_5xx")&&r.reason().equals("cap_write_busy"))
            ||(r.httpStatus()==429&&r.status().equals("rejected_429")&&r.reason().equals("cap_cooldown")));}
    static void validate(Phase18Plan p,Phase18Plan.Case c){
        var w=c.workload();var s=w.schedule();var n=p.counts(c);
        Phase18Plan.require(Set.of("CAP-0","CAP-1","CAP-2","CAP-3").contains(p.stage())&&p.cases().size()==1,"CAP stage/single case");
        Phase18Plan.require(Set.of("fixed-rate","smoke-s2","users").contains(s.kind())&&s.issuedEpochMillis()>0
                &&s.expiresEpochMillis()>s.issuedEpochMillis()&&(Phase18Cap1.enabled(p)
                ||s.expiresEpochMillis()-s.issuedEpochMillis()<=3_600_000&&p.bounds().totalSeconds()<=3600),"CAP top-level single expiry");
        if(Phase18Cap1.enabled(p))Phase18Cap1.validate(p);
        Phase18Plan.require(c.runtime().mode().equals("group")&&c.runtime().measurement()&&c.runtime().segmentBytes()==33554432
                &&w.reuseMarginMillis()==1000&&w.requestMillis()==10000,"CAP runtime/cooldown invariant");
        Phase18Plan.require(w.writeInFlight()<=128&&w.readInFlight()<=128&&w.wsConnections()<=3200,"CAP concurrency ceiling");
        Phase18Plan.require(w.wsConnections()<=200,"CAP native/socket support above WS200 not verified: TOOL_LIMIT");
        Phase18Plan.require(!scheduled(w)||w.writeInFlight()>0&&w.readInFlight()>0,"CAP scheduled dispatch slots");
        Phase18Plan.require(n.writes()+n.reads()<=Phase18K6Session.requestLimit(p)&&n.wsDeliveries()<=Phase18K6Session.deliveryLimit(p),"CAP exact analysis/receipt allocation TOOL_LIMIT");
        // terminal별 분석2300bytes와 기본128MiB 예약. WS 시각은 파일 보유·연결별 분석
        Phase18Plan.require(134217728L+(n.writes()+n.reads())*2300L<=Phase18K6Session.analysisMemory(p),"CAP analysis heap TOOL_LIMIT");
        if(p.stage().equals("CAP-0")){
            boolean s1=c.role().equals("cap-smoke-s1")&&s.kind().equals("fixed-rate")&&w.writeRate()==2&&w.readRate()==2
                    &&w.wsConnections()==2&&w.warmupSeconds()==2&&w.measurementSeconds()==5&&w.inputWindowSeconds()==5&&w.bootstrap()&&w.userMode().equals("once");
            boolean s2=c.role().equals("cap-smoke-s2")&&s.kind().equals("smoke-s2")&&s.activeUsers()==2&&w.userPool()==2&&w.userMode().equals("reuse")
                    &&w.wsConnections()==2&&w.warmupSeconds()==0&&w.measurementSeconds()==220&&!w.bootstrap()&&w.writeRate()==0&&w.readRate()==0;
            Phase18Plan.require(s1||s2,"CAP-0 registered S1/S2 only");
        }else{
            Phase18Plan.require((c.role().equals("cap-target")||Phase18Cap1.comparison(p))&&w.inputWindowSeconds()==10&&w.inputTolerancePpm()==1000,"CAP target input policy");
            if(p.stage().equals("CAP-3"))Phase18Plan.require(s.kind().equals("users")&&s.activeUsers()>0&&s.activeUsers()<=w.wsConnections()
                    &&s.writePeriodSeconds()==200&&s.readPeriodSeconds()==Math.max(60,(w.wsConnections()+99)/100)&&w.userPool()==s.activeUsers()&&w.userMode().equals("reuse")&&w.warmupSeconds()==30
                    &&!w.bootstrap()&&w.writeRate()==0&&w.readRate()==0&&w.wsConnections()>0,"CAP-3 compact schedule");
            else Phase18Plan.require(s.kind().equals("fixed-rate")&&w.warmupSeconds()==30&&Set.of(60L,120L,1200L).contains(w.measurementSeconds())
                    &&w.userMode().equals("reuse")&&w.userPool()>=pool(w),"CAP deterministic reuse preparation");
        }
        Phase18Plan.require(w.drainMillis()<=180000&&w.inputTolerancePpm()==1000,"CAP finite drain/input");
    }
}
