package dev.cgt.benchmark;

import dev.cgt.pixelplace.flush.application.*;
import dev.cgt.pixelplace.wal.application.WalRetentionResult;
import dev.cgt.pixelplace.wal.infra.*;
import org.aopalliance.intercept.MethodInterceptor;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.context.ConfigurableApplicationContext;
import java.nio.file.*;
import java.util.*;

/** benchmark context의 기존 transaction/storage에 위임하는 관측기. 새로운 flush·scan·내구성 호출 없음 */
final class Phase18Observation implements BeanPostProcessor {
    private final Phase18Trace trace;
    Phase18Observation(Phase18Trace trace) { this.trace = trace; }
    void install(ConfigurableApplicationContext context) {
        context.addBeanFactoryPostProcessor(beans -> {
            beans.registerSingleton("phase18Trace",trace);
            ((AbstractBeanDefinition)beans.getBeanDefinition("windowsWalFileDurability")).setBeanClass(Phase18WindowsObservation.class);
            ((AbstractBeanDefinition)beans.getBeanDefinition("segmentedWalStorage")).setBeanClass(Phase18ObservedStorage.class);
            beans.addBeanPostProcessor(this);
        });
    }
    @Override public Object postProcessAfterInitialization(Object bean, String name) {
        String type = AopUtils.getTargetClass(bean).getSimpleName();
        Set<String> methods = switch (type) {
            case "FlushPlanCaptureService" -> Set.of("capturePlan");
            case "FlushWorker" -> Set.of("flushOnce");
            case "FlushWalRetention" -> Set.of("onCommitConfirmed","retryIfEligible");
            case "Phase18ObservedStorage" -> Set.of("deleteCommittedPrefix");
            default -> bean instanceof FlushTransactionExecutor ? Set.of("execute") : Set.of();
        };
        if (methods.isEmpty()) return bean;
        var proxy = new ProxyFactory(bean); proxy.setProxyTargetClass(true);
        proxy.addAdvice((MethodInterceptor) invocation -> {
            String operation = invocation.getMethod().getName();
            if (!methods.contains(operation)) return invocation.proceed();
            long id = trace.begin(operation,name); Throwable failure = null; Object result = null;
            try { result = invocation.proceed(); return result; }
            catch (Throwable problem) { failure = problem; throw problem; }
            finally {
                String detail = "completed";
                try {
                    FlushPlan plan = result instanceof FlushPlan p ? p : invocation.getArguments().length == 1
                            && invocation.getArguments()[0] instanceof FlushPlan p ? p : null;
                    if (plan != null) detail = "records="+plan.walRecords().size()+";tiles="+plan.tileSnapshots().size()
                            +";dirty="+plan.drainedDirtyTiles().size()+";checkpoint="+plan.expectedLastFlushedEventSeq()+";target="+plan.flushTargetEventSeq();
                    if (result instanceof FlushTransactionResult r) detail += ";outcome="+r.outcome();
                    if (result instanceof Enum<?> e) detail = e.name();
                    if (result instanceof WalRetentionResult r) detail = "deleted="+r.deletedFiles()+";delayed="+r.delay().isPresent();
                } catch (RuntimeException observerFailure) { trace.incomplete(); }
                catch(Error observerError){Phase18Trace.preserveError(failure,observerError);}
                trace.end(id,operation,name,detail,failure);
            }
        });
        return proxy.getProxy();
    }
    /** 종료 뒤의 streaming hash. 파일 수/bytes 상한을 먼저 검사하고 기존 fixture의 1MiB 제약과 분리 */
    static Map<String,String> snapshot(Path wal, long maximumBytes, int maximumFiles) throws Exception {
        var result = new TreeMap<String,String>(); long bytes = 0;
        try (var paths = Files.newDirectoryStream(wal.getParent())) {
            for (Path p : paths) {
                Phase18Plan.require(p.getFileName().toString().matches("pixel-place\\.wal(?:\\.seg-[0-9]{19})?"),"owned WAL entry");
                BenchmarkGuards.rejectLinks(p); long size = Files.size(p); bytes = Math.addExact(bytes,size);
                Phase18Plan.require(result.size() < maximumFiles && bytes <= maximumBytes,"WAL snapshot budget");
                result.put(p.getFileName().toString(),size+":"+BenchmarkJson.hash(p));
                Phase18Plan.require(Files.size(p)==size,"WAL changed while hashing");
            }
        }
        return result;
    }
}
