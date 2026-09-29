package dev.cgt.benchmark;

import dev.cgt.pixelplace.measurement.PixelMeasurement;
import dev.cgt.pixelplace.wal.application.*;
import dev.cgt.pixelplace.wal.infra.*;
import org.aopalliance.intercept.MethodInterceptor;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.*;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.Ordered;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.nio.file.*;
import java.util.*;

/** 명시 등록한 C context에만 적용되는 관측 BPP. 이미 존재하는 transaction proxy 자체에 위임 */
public final class Phase17CObservation implements BeanPostProcessor, BeanFactoryAware, Ordered {
    private final Phase17CTrace trace; private final Path wal; private BeanFactory factory;
    private volatile Map<String, String> before = Map.of(), after = Map.of();
    private volatile boolean preparationOutsideTransaction;
    private final Map<String, Object> targets = new HashMap<>();
    public Phase17CObservation(Phase17CTrace trace, Path wal) { this.trace = trace; this.wal = wal; }
    @Override public int getOrder() { return Ordered.LOWEST_PRECEDENCE; }
    @Override public void setBeanFactory(BeanFactory factory) throws BeansException { this.factory = factory; }
    public void install(ConfigurableApplicationContext context) {
        context.addBeanFactoryPostProcessor(beans -> {
            beans.registerSingleton("phase17CTrace", trace);
            // bean 생성 수명주기를 우회하지 않고 기존 정의의 구현만 관측 subclass로 연결. @PreDestroy/의존 종료 순서 보존
            ((AbstractBeanDefinition) beans.getBeanDefinition("windowsWalFileDurability")).setBeanClass(Phase17CWindowsObservation.class);
            ((AbstractBeanDefinition) beans.getBeanDefinition("segmentedWalStorage")).setBeanClass(Phase17CObservedStorage.class);
            setBeanFactory(beans); beans.addBeanPostProcessor(this);
        });
    }
    @Override public Object postProcessAfterInitialization(Object bean, String name) {
        String type = AopUtils.getTargetClass(bean).getSimpleName();
        Set<String> methods = switch (type) {
            case "FileWalStoragePreparation" -> Set.of("prepareForRecovery");
            case "InMemoryTileBoard" -> Set.of("initializeAllWhite", "loadAll", "applyReplayRecord");
            case "EventSeqManager" -> Set.of("initializeLastIssued");
            case "ServiceReadiness" -> Set.of("markReady");
            case "SynchronizedDirtyTileTracker" -> Set.of("markDirty", "drainDirtyTiles", "restoreDirtyTiles");
            case "FlushWorker" -> Set.of("flushOnce");
            case "FileWalAppender" -> Set.of("appendAndFsync", "appendBatchAndFsync");
            case "Phase17CObservedStorage", "SegmentedWalStorage" -> Set.of("deleteCommittedPrefix");
            default -> Set.of();
        };
        if (methods.isEmpty()) return bean;
        targets.put(name, bean);
        var proxy = new ProxyFactory(bean); proxy.setProxyTargetClass(true);
        proxy.addAdvice((MethodInterceptor) invocation -> {
            String op = invocation.getMethod().getName();
            if (!methods.contains(op)) return invocation.proceed();
            if (op.equals("prepareForRecovery")) captureBefore();
            long id = trace.begin(op, name); Throwable failure = null; Object result = null;
            try { result = invocation.proceed(); return result; }
            catch (Throwable problem) { failure = problem; throw problem; }
            finally {
                Object metadata = "completed";
                try { metadata = result instanceof Collection<?> c ? c.size() : result instanceof Enum<?> e ? e.name()
                        : result instanceof WalRetentionResult r ? "deleted=" + r.deletedFiles() + ";delayed=" + r.delay().isPresent() : "completed";
                if (op.equals("initializeLastIssued")) metadata = invocation.getArguments()[0];
                } catch (RuntimeException observationFailure) { trace.incomplete(); }
                trace.end(id, op, name, metadata, failure);
                if (op.equals("prepareForRecovery")) captureAfter();
            }
        });
        return proxy.getProxy();
    }
    private void captureBefore() {
        try { preparationOutsideTransaction = !TransactionSynchronizationManager.isActualTransactionActive(); before = snapshot(wal); }
        catch (Exception failure) { trace.incomplete(); }
    }
    private void captureAfter() {
        try { after = snapshot(wal); if (!before.equals(after) || !preparationOutsideTransaction) trace.incomplete(); }
        catch (Exception failure) { trace.incomplete(); }
    }
    public Map<String, Object> preparation() { return Map.of("before", before, "after", after, "bytesPreserved", before.equals(after), "outsideTransaction", preparationOutsideTransaction); }
    public Map<String, String> preparationFiles() { return before; }
    public Object original(String beanName) { return targets.get(beanName); }
    public static Map<String, String> snapshot(Path wal) throws Exception {
        var result = new TreeMap<String, String>(); if (!Files.exists(wal.getParent())) return result;
        long total = 0;
        try (var paths = Files.list(wal.getParent())) {
            for (Path p : paths.sorted().toList()) {
                if (!p.getFileName().toString().matches("pixel-place\\.wal(?:\\.seg-[0-9]{19})?")) throw new IllegalStateException("Unexpected owned WAL entry");
                BenchmarkGuards.rejectLinks(p); total += Files.size(p);
                if (total > 1048576) throw new IllegalStateException("Case WAL byte budget");
                result.put(p.getFileName().toString(), Files.size(p) + ":" + BenchmarkJson.hash(p));
            }
        }
        return result;
    }
}
