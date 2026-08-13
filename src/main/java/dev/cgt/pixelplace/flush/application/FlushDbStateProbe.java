package dev.cgt.pixelplace.flush.application;

/* ambiguous transaction 종료와 직렬화된 checkpoint/tile mode 관측 application port */
public interface FlushDbStateProbe {

    /* 별도 transaction에서 main checkpoint lock과 전체 tile metadata를 함께 관측 */
    FlushDbState probe();
}
