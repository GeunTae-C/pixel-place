package dev.cgt.pixelplace;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

// Spring Boot component graph 기동 진입점
// startup recovery 실행과 readiness 전환은 StartupRecoveryRunner 이하의 별도 경계가 담당
@SpringBootApplication
public class PixelPlaceApplication {

	public static void main(String[] args) {
		SpringApplication.run(PixelPlaceApplication.class, args);
	}

}
