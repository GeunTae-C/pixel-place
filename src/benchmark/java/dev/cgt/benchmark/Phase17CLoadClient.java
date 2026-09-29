package dev.cgt.benchmark;

/** C의 독립 HTTP JVM. 기존 발생기를 호출하며 실패 뒤 강제 process 종료 없음 */
public final class Phase17CLoadClient {
    private Phase17CLoadClient() { }
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("C trial identity required");
        Phase15LoadClient.runC(args[0]);
    }
}
