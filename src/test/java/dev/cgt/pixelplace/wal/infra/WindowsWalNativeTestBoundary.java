package dev.cgt.pixelplace.wal.infra;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.HashSet;
import java.util.Set;

/** 실제 kernel32 호출을 위임하며 이번 fixture의 native handle 취득/close를 계수. native 반환 대체 없음 */
final class WindowsWalNativeTestBoundary implements AutoCloseable {
    private final Set<Long> owned=new HashSet<>();
    int opened, closed, flushes;
    final WindowsWalNativeBridge bridge=new WindowsWalNativeBridge(this::load,Native::getLastError);

    private WindowsWalNativeBridge.Kernel32 load() {
        var actual=Native.load("kernel32",WindowsWalNativeBridge.Kernel32.class);
        return (WindowsWalNativeBridge.Kernel32)Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{WindowsWalNativeBridge.Kernel32.class},(proxy,method,args)-> {
                    Object result;
                    try { result=method.invoke(actual,args); }
                    catch(InvocationTargetException failure){throw failure.getCause();}
                    if(method.getName().equals("CreateFileW") && !WindowsWalNativeBridge.invalid((Pointer)result)) {
                        if(!owned.add(Pointer.nativeValue((Pointer)result)))throw new AssertionError("Handle reused while owned");
                        opened++;
                    }
                    if(method.getName().equals("CloseHandle") && (int)result!=0) {
                        if(!owned.remove(Pointer.nativeValue((Pointer)args[0])))throw new AssertionError("Unowned/double close");
                        closed++;
                    }
                    if(method.getName().equals("FlushFileBuffers") && (int)result!=0)flushes++;
                    return result;
                });
    }
    @Override public void close() {
        if(!owned.isEmpty() || opened!=closed)throw new AssertionError("Native handle ownership mismatch");
        System.out.println("NATIVE_HANDLES opened="+opened+" closed="+closed+" flushes="+flushes);
    }
}
