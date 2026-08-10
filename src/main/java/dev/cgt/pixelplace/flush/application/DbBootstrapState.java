package dev.cgt.pixelplace.flush.application;

/* DB tile row shape와 checkpoint 조합의 runtime bootstrap 판정 결과 */
public enum DbBootstrapState {
    BOOTSTRAP_PENDING,
    INITIALIZED,
    INCONSISTENT
}
