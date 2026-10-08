# 1.1.0 검증 기록

- Paper API 1.21.11-R0.1-SNAPSHOT / Java 21
- Maven verify: BUILD SUCCESS
- JUnit/MockBukkit 테스트 29개: 실패 0, 오류 0, 건너뜀 0
- 실제 Paper 서버 다중 플레이어 테스트는 수행하지 않음
- JAR SHA-256: `c687f2c78f8ba1808ddda6c27c8688e4de2cdd1d396eeeb3982c43ff69d9791f`

# 1.2.0 현재 검증 결과

- `config.yml`, `plugin.yml` YAML 파싱 성공
- Java 파일 중괄호 구조 검사 성공
- 현재 작업 환경에서 Java 21/Maven 도구 재설치가 네트워크 정책으로 막혀 Maven 컴파일과 MockBukkit 테스트는 실행하지 못함
- 실제 Paper 서버 다중 플레이어 테스트는 수행하지 않음
