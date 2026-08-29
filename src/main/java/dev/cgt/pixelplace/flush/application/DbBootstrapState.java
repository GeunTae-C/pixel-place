package dev.cgt.pixelplace.flush.application;

/* DB tile row shape와 checkpoint 조합의 runtime bootstrap 판정 결과 */
public enum DbBootstrapState {
    /* checkpoint 0과 tile 0 rows가 함께 관측된 최초 full snapshot 대기 상태 */
    BOOTSTRAP_PENDING,

    /* canonical z=0 전체 1,024 rows가 형성되어 incremental flush 가능한 상태 */
    INITIALIZED,

    /* checkpoint와 전체 tile key shape가 허용 조합에 속하지 않는 fail-fast 상태 */
    INCONSISTENT
}
