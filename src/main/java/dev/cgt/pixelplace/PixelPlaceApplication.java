package dev.cgt.pixelplace;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

// 애플리케이션의 최소 시작점이다.
// 실제 부팅 복구는 별도 runner가 연결되며, 이 클래스는 그 흐름이 시작될 진입점만 제공한다.
@SpringBootApplication
public class PixelPlaceApplication {

	public static void main(String[] args) {
		SpringApplication.run(PixelPlaceApplication.class, args);
	}

}
