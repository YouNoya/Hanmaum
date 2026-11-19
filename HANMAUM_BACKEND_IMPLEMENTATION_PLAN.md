# 한마음 백엔드 API 구현계획서

> 구현 시 `superpowers:executing-plans`로 아래 작업을 순서대로 진행한다. 체크박스는 단계별 검증에 사용한다.

**목표** — 가족이 소식을 남기고, 소유자가 AI 제안을 검토해 확정하며, 원본을 삭제한 뒤 필요한 돌봄 항목을 안전하게 재확인할 수 있는 흐름을 완성한다. 나머지 API는 선행 계약에 맞춰 순차적으로 연결한다.

**구조** — 기존 `/api`의 인증, 공간 접근, 제안·변경 요청, 항목 버전·잠금, 근거·이력, 반복 요청 경계를 재사용한다. 파괴적 요청은 영향 조회와 실행에서 동일한 영향 산정 규칙을 사용한다. 화면 연동 검증과 BE 구현 검증은 구분한다.

**기술** — Java 21, Spring Boot, Gradle Wrapper, PostgreSQL·Flyway, Testcontainers. FE는 React·Vite·TypeScript, 기존 Axios 클라이언트 및 TanStack Query 기반이다.

**근거** — [한마음 API 명세표](https://app.notion.com/p/c5a8a2cf6d4a83aaa7170105ceeebf43), [API 명세서 본문](https://app.notion.com/p/4718a2cf6d4a82a1b4bb0135c4662e24), [#47](https://github.com/Team-Hanmaum/hanmaum-backend/issues/47), 각 저장소 `AGENTS.md`. 기준은 로컬 FE `dev` `adb4cae`, BE `dev` 및 #47 브랜치 `f78ffb2`. 이 문서의 미정 정책은 구현 권고안이며 팀 결정 또는 제품 명세 변경을 뜻하지 않는다.

## 1. 프론트엔드 화면 점검

`hanmaum-frontend/src/routes/Router.tsx`에서 사용자 화면 경로 54개를 확인했다. 화면을 공유하는 경로도 각각 집계했다. 개발용 컴포넌트 미리보기, 404·오류 화면, 공간 루트의 리다이렉트는 제외했다. 화면 경로 및 구현 파일을 코드로 확인한 결과이며 브라우저 조작, 배포 환경, Figma 전체 대조는 이 점검 범위 밖이다.

| 화면 영역 | 경로 수 | 확인 결과 |
| --- | ---: | --- |
| 진입·온보딩·공간 선택/생성 | 6 | 랜딩, 로그인, 초대 진입, 온보딩, 공간 선택·생성. 로그인 버튼은 Mock이며, 부모님 동의는 안내 문구만 있다. |
| 계정 | 5 | 내 계정, 로그아웃, 탈퇴 안내·소식 확인·완료 화면이 있다. `useMe`·`useLogout` 훅과 API 모듈은 존재하지만 화면에서 호출하지 않는다. |
| 하단 탭 | 5 | 홈·돌봄·소식·가족·전체. 업무 데이터는 Mock에 의존한다. |
| 소식 | 4 | 작성·상세·삭제 확인·삭제 결과. 분석 상태·재시도와 삭제 영향·결과가 Mock이다. |
| 돌봄 항목 | 15 | 추가·편집·담당자·상태·답변·삭제, 요청함, 관리·상세·근거·이력·재확인, 공유·수신 화면이 있다. 화면들은 실제 항목 API에 연결돼 있지 않다. |
| 제안·검토 | 8 | 목록, 원본별 검토, 근거·편집·대상 선택, 확인·결과, 변경 요청 상세. 소유자·구성원 동작은 Mock이다. |
| 공간 정보 | 4 | 상세, 편집, 요청 목록·상세가 있다. 정보 수정과 구성원 요청은 Mock이다. |
| 가족 관리·초대 | 7 | 구성원 목록·제외, 나가기, 공간 삭제, 소유권 이전·확인, 가족 초대가 있다. 실제 초대·공간 수명 주기 API 연동이 필요하다. |

**판정** — 핵심 업무의 화면과 이동 경로는 폭넓게 구현됐다. 실사용 기준으로는 미완료다. 현재 업무 화면의 실제 네트워크 연결을 확인할 수 없었고, 동의 요청·기록 화면, 승인 요청 전용 내부 알림·검토 이동, 토큰 기반 공유 수신, 카카오 SDK 전송은 각각 후속 작업이다. 기존 요청 목록 화면은 승인 알림과 역할이 다르다. 공유 링크 프리뷰는 현재 공간·항목 ID 경로를 Mock으로 사용한다. `src/routes/README.md`의 일부 문구는 예전 준비 상태를 설명하므로 실제 라우터와 화면 파일을 기준으로 판단했다.

**FE 연결 순서** — 기존 API로 먼저 ① OAuth 복귀·세션·내 정보·로그아웃·CSRF, ② 공간·초대·구성원과 접근 경계, ③ 소식·분석·제안·승인·홈, ④ 항목 상세·이력·근거·잠금과 수정 흐름을 연결한다. 그다음 아래 신규 API를 단계별로 붙인다. 기존 `src/lib/api/apiClient.ts`를 사용하고 Mock 해제 시 실제 세션과 공간 권한을 확인한다. 이 작업은 백엔드 이슈와 분리한다.

## 2. 공통 기준

- BE 기준 API 56개 중 42개는 구현이 확인됐고 14개는 경로가 미등록이다. 아래 1~12단계는 현행 영향 확인값 계약을 유지한 권고 순서다. 13~14단계는 조건부 항목이다. 화면의 존재만으로 새 API를 확정하지 않는다.
- 기존 Spring 세션·쿠키, 변경 요청 CSRF, `@CurrentUser`, `ApiResponse`, `ApiException`, 기존 상태·오류를 우선 사용한다. 다른 공간의 ID 조합을 검증하고 공간 참여·역할·대상 권한을 실행 시 재확인한다.
- `expected*Version`은 기존 규격대로 십진 문자열을 사용한다. 쓰기 API는 명세에 제시된 `clientRequestId`를 채택하고 기존 `CommandDigest`·`CommandReceiptRepository`로 재시도와 충돌을 처리한다. 인증이나 접근 권한을 잃은 요청자에게 결과·본문을 노출하지 않는다. 삭제된 본문을 명령 응답 재전송으로 복원할 수 없어야 한다.
- **영향 확인값 권고** — 기존 `CommandDigest.digest(String... parts)`에 작업 구분값, 포맷 버전, 요청자·대상 ID, 대상 버전, 정렬한 영향 대상 ID·관련 상태/버전을 넣어 HMAC을 계산한다. 집계 숫자만 해시하지 않는다. 확인값은 저장하지 않고 실행 시 현재 권한과 그래프로 재검증한다. 별도 TTL 없이 상태·권한 변동 및 키 교체로 무효화하는 것을 첫 버전의 권고안으로 둔다. 생성·실행의 원자성은 트랜잭션 및 실제 경쟁 테스트로 입증한다.
- **제품 정책 권고** — 최종 반영은 소유자 또는 문서가 명시한 직접 실행 예외를 따른다. 가족 요청은 기존 변경 요청 큐를 쓴다. 승인 병목에는 우선 기존 요청함에서 대기 건과 검토 진입을 드러낸다. 승인 요청 도착 알림은 별도 기능 범위로 검토한다. 부모님 정보 수집 절차, 외부 열람 범위, 운영 데이터 보관·정리는 출시 전 팀이 확정해야 한다.
- 단계별로 기존 테스트에 실패 사례를 먼저 추가하고 테스트가 기대한 이유로 실패하는지 확인한다. 해당 작업의 서비스·DTO·Swagger와 연결을 구현하고 관련 테스트와 `.\gradlew.bat check bootJar`를 실행한다. PostgreSQL 통합 테스트는 격리된 Testcontainers DB에서 수행한다. 공개 API 1개당 이슈·브랜치·PR 1개를 기본으로 하고, 선행 PR을 `dev`에 반영한 후 후속 작업을 시작한다. 기존 API 동작 추가가 필요하면 이슈 범위를 별도로 잡는다. #47은 기존 이슈·브랜치를 쓴다.

### 리뷰 중점

- 삭제 전 열어 둔 제안·편집 잠금과 지연된 AI 결과가 삭제 내용을 복원하지 못하는지 확인한다.
- 독립 근거가 하나 남아도 원본에 의존한 값이나 과거 문서가 화면·공유에 남지 않는지 확인한다.
- 중복 제출과 버전 충돌에서 잘못된 두 번째 기록이나 부분 반영이 생기지 않는지 확인한다.
- 탈퇴·공간 삭제에 성공한 뒤 응답을 잃어도 `401`·`404`를 근거로 성공이나 실패를 단정하지 않는지 확인한다.
- 공유 후 멤버 제외, 원본 삭제, 항목 재확인 진입, 토큰 만료 시 이전 URL과 캐시에서 내용이 보이지 않는지 확인한다.

## 3. 구현 순서

BE 경로 표기는 `hanmaum-backend/src/main/java/com/hanmaum/backend/` 아래를 기준으로 한다. 테스트 클래스는 `src/test/java/com/hanmaum/backend/`에 둔다. 파일·클래스 신설 표기는 권고 설계다.

### 1. #47 소식 원본 삭제 영향 조회 — `GET /api/spaces/{spaceId}/source-records/{recordId}/deletion-impact`

**구현 완료** — 로컬 브랜치 `feat/47-source-record-deletion-impact`, 커밋 `7dbaafd`. 전체 백엔드 테스트 1,249개와 JAR 빌드가 통과했다. 로컬 Swagger에 등록되었으며, `affectedItems[].requiresReconfirmation`으로 독립 근거 여부와 삭제 후 재확인 필요 여부를 구분한다.

**연결** — `record/controller/SourceRecordController.java`, `record/repository/SourceRecordQueryRepository.java`, `careitem/repository/CareItemProvenanceRepository.java`, `proposal/repository/ProposalProvenanceRepository.java`, `global/command/CommandDigest.java`.

- [ ] `SourceRecordDeletionImpactIntegrationTests`에서 소유자·현재 참여 작성자 허용, 다른 가족·탈퇴한 작성자 차단, 공간 불일치와 삭제된 원본, 영향 0·빈 목록, 직접·간접 중복 제거, 별도 유효 근거를 검증한다. GET 전후 원본·연결 상태가 동일해야 한다.
- [ ] `record/service/SourceRecordDeletionImpactService.java`에 `preview(UUID actorUserId, UUID spaceId, UUID recordId)`를 추가한다. 권한 확인 후 읽기 전용 일관 스냅샷에서 원본→근거→제안·문서→항목·이력의 집합을 구한다. `counts`는 종류별 고유 ID 기준이며 이미 제거된 사본 자체는 제외하되 아직 노출 가능한 하위 파생물은 포함한다. 재확인 필요 여부와 별도 근거 존재 여부를 분리한다.
- [ ] 기존 응답 필드 `recordId`, `recordVersion`, `counts`, `affectedItems`, `impactToken`을 유지한다. FE는 1차로 기존 접근 권한이 적용된 항목 조회에서 표시명을 가져오고 조회할 수 없는 항목은 일반 안내를 표시한다. 현재 화면의 유형·제목 표시를 위해 원본·파생 본문을 응답에 추가하지 않는다.
- [ ] 같은 그래프에 같은 확인값, 집계가 같아도 영향 대상 교체·근거 유효성 변경 시 다른 확인값을 검증한다. 2단계에서 같은 영향 산정 메서드를 호출할 수 있도록 산정과 화면 응답 조립을 분리한다.

### 2. 소식 원본 삭제 실행 — `POST /api/spaces/{spaceId}/source-records/{recordId}/deletions`

**구현 완료** — 로컬 브랜치 `feat/50-source-record-deletion`, 커밋 `ab7e93f`. 전체 백엔드 테스트 1,253개와 JAR 빌드가 통과했고 로컬 Swagger에서 삭제 경로를 확인했다.

**선행** — 1단계. `record` 삭제 서비스와 `analysis/service/AnalysisExecutionService.java`, `analysis/repository/AnalysisRepository.java`, `careitem/repository/CareItemApplicationRepository.java`, 제안·변경 요청 저장소와 함께 검증한다.

- [ ] `SourceRecordDeletionIntegrationTests`에서 `expectedRecordVersion` 변경은 `VERSION_CONFLICT`, 같은 버전에서 영향 변경은 `DELETION_IMPACT_CHANGED`, 같은 요청 ID 재전송은 재실행 없음으로 검증한다. 원본·근거·제안·검토 문서·이력에서 파생 본문 미노출을 확인한다.
- [ ] 실행 시 권한과 원본 버전을 재검사하고, 동시 제안 적용·분석 저장·항목 편집과 충돌하는 저장 경로에서 영향 재계산과 제거를 일관되게 보호한다. 기존 잠금 순서를 먼저 추적하고, 동일한 순서를 쓰는 경계에 필요한 최소 보호를 넣는다. 만료 전 타인 편집 잠금도 삭제를 지연시키지 않도록 영향 항목의 버전을 올리고 잠금을 무효화한다.
- [ ] 필드별 독립 출처를 입증할 수 있는 값만 유지한다. 기존 데이터에서 혼합 출처를 구분할 수 없으면 영향 값과 과거 문서를 가리고 항목을 재확인 대상으로 전환한다. 200은 서비스 내 제거 완료 뒤에만 반환한다. 현재 원본·근거 상태를 분석 결과 저장 직전에 검증해 늦게 도착한 결과를 폐기한다.
- [ ] 조회 직후 동시 적용·분석 완료, 트랜잭션 중간 실패와 재시도, 옛 명령 응답, 재확인 진입 후 목록·현황판·상세·이력 응답을 검증한다. FE가 안정적으로 결과 화면을 열 수 있도록 실행 응답의 ID와 `reconfirmationItemIds`를 계약에 맞춘다.

### 3. 돌봄 항목 근거 재확인 — `POST /api/spaces/{spaceId}/items/{itemId}/reconfirmations`

**구현·실행 검증 완료** — #53 로컬 브랜치 `feat/53-care-item-reconfirmation`, 커밋 `5c6af99`. 전체 테스트 1,268개와 JAR 빌드가 통과했다. 기존 로컬 개발 DB에서 Flyway V16 적용, 서버 health와 Swagger 등록을 확인했다. FE 재확인 화면은 모의 저장을 사용 중이며 실제 API 연동이 남아 있다.

**선행** — 2단계. `careitem/service/EditLockService.java`, `careitem/repository/EditLockRepository.java`, `careitem/service/CareItemApplicationService.java` 및 근거·이력 저장 경로를 사용한다.

- [ ] `CareItemReconfirmationIntegrationTests`에서 일반 편집 경로가 재확인 대상을 계속 차단하고, 소유자만 명시적인 재확인 목적의 잠금을 획득·갱신할 수 있는지 검증한다. 현행 잠금은 `needs_reconfirmation` 항목을 가용 대상으로 보지 않으므로 잠금 조건을 명시적으로 분리한다.
- [ ] `expectedItemVersion`, `leaseToken`, 새 `values`, 새 `basis`, 선택된 유효 `evidenceIds`, `clientRequestId`를 검증한다. 삭제된 과거 값 자동 복원 없이 새 문서·근거·이력을 한 트랜잭션으로 저장한다. 기존 업무 상태는 유지하고 검증에 성공한 경우에만 재확인 플래그를 해제한다.
- [ ] 다른 공간·삭제된 근거, 유효기간이 지난 잠금, 역할 변경, 동일 요청 재시도와 저장 후 현황판 재노출을 검증한다.

### 4. 확인사항 답변 제출 — `POST /api/spaces/{spaceId}/items/{itemId}/answers`

**구현·실행 검증 완료** — #55 로컬 브랜치 `feat/55-question-answer-submission`, 커밋 `ba0b91d`. 전체 테스트 1,272개와 JAR 빌드가 통과했다. 기존 개발 DB에서 V18 적용과 로컬 서버 health·Swagger 등록을 확인했다. FE의 실제 API 연동과 상세 답변 이력의 페이지네이션 설계는 후속 범위다.

**연결** — `proposal/service/ChangeRequestService.java`, `proposal/repository/ChangeRequestRepository.java`, `careitem/service/CareItemApplicationService.java`. 기존 요청 생성·조회·검토·승인 흐름을 확장한다.

- [ ] 현재 참여자가 열린 질문에 `answer`, `basis`, `expectedItemVersion`, `clientRequestId`로 제출하는 테스트를 추가한다. 비어 있는 답변·근거, 다른 공간, 해소된 질문, 동일 요청 ID 충돌을 검증한다. 제출 시 질문은 바뀌지 않아야 한다.
- [ ] 답변과 확인 근거를 구분해 저장하고, 제출에는 편집 잠금을 요구하지 않는다. 소유자 본인 답변도 확인 단계를 거쳐 반영한다. 승인 측은 기존 `request-applications`에 답변 해소 동작을 추가해 최신 검토 토큰·항목 버전·잠금을 검증한다. 이 동작은 현재 구현에 없으므로 `answers` 공개 전후의 승인 연결을 같은 출시 묶음으로 검증한다.
- [ ] 두 가족의 답변이 별도 제출되고, 소유자의 첫 유효한 승인만 질문을 해소하며, 나머지 오래된 승인 요청은 현재 상태에서 차단되는지 검증한다. 13단계의 조건을 충족할 때만 별도 해소 경로를 검토한다.

### 5. 돌봄 항목 일반 삭제 — `POST /api/spaces/{spaceId}/items/{itemId}/deletions`

**연결** — `careitem/controller/CareItemController.java`, `careitem/repository/CareItemApplicationRepository.java`, `careitem/service/EditLockService.java` 및 기존 항목 읽기 경로.

- [ ] 소유자 직접 삭제, 다른 가족 차단, 타인 편집 잠금 충돌, `expectedItemVersion` 검증, 동일 요청 재시도를 테스트한다. 기존 원본과 정책상 유효한 이력·근거는 보존하고 목록·현황판·신규 공유에서 제외한다.
- [ ] 항목 삭제·버전 증가·이력·잠금 정리를 원자적으로 수행한다. 가족 삭제 요청 UI까지 출시할 경우 기존 `change-requests`에 `DELETE_ITEM` 동작과 소유자 승인 처리를 별도 범위로 추가한다. 승인과 직접 삭제는 동일한 업무 처리 경로를 사용한다.

### 6. 돌봄 공간 정보 수정 — `PATCH /api/spaces/{spaceId}`

**연결** — `carespace/controller/CareSpaceController.java`, `carespace/service/CareSpaceCreationService.java`, `carespace/repository/CareSpaceRepository.java`.

- [ ] 소유자의 `subjectLabel` 수정, 생성과 같은 정규화·중복 판정, `expectedSpaceVersion` 충돌, 가족 직접 수정 거부를 테스트한다.
- [ ] 명세에 있는 호칭 수정만 연결한다. 기존 생성 규칙과 DB 제약을 사용하고 공간 버전을 증가시킨다. 공간 전체 편집 잠금은 도입하지 않는다. 일반 구성원 요청을 출시하려면 공간 대상 변경 요청과 소유자 승인 처리를 별도 계약으로 추가한다. FE의 요청 화면만으로 추가 공간 필드나 새 API를 확정하지 않는다.

### 7. 돌봄 공간 삭제 영향 조회 — `GET /api/spaces/{spaceId}/deletion-impact`

**연결** — `carespace/repository/CareSpaceAccessRepository.java`, 공간 및 구성원 조회, 1단계에서 사용한 확인값 패턴.

- [ ] 소유자 접근, 다른 가족 거부, 읽기 무변경, 삭제·초대·분석·공유에 영향이 생길 때 확인값 변경을 테스트한다.
- [ ] 실제 8단계 삭제 대상의 같은 산정 기준을 사용한다. 소유자와 공간 버전, 삭제 대상과 보존해야 할 데이터 구분을 검증한다. FE의 일반 삭제 안내와 별개로 실행 계약에 필요한 `impactToken`을 반환한다.

### 8. 돌봄 공간 전체 삭제 — `POST /api/spaces/{spaceId}/deletions`

**선행** — 7단계. `carespace/service/MembershipLifecycleService.java`의 권한·잠금 패턴 및 원본 삭제에서 확인한 분석 저장 차단을 활용한다.

- [ ] `expectedSpaceVersion`, `impactToken`, `clientRequestId`로 실행한다. 동시 초대 참여·분석 완료·항목 반영과의 경쟁, 부분 실패 시 완료 응답 금지, 타 공간 불변을 테스트한다.
- [ ] 접근·새 쓰기를 먼저 막는 일관된 경계에서 원본·항목·요청·근거·이력·초대 등 공간 소속 데이터를 제거한다. 11~12단계에서 공유를 추가할 때 이 정리 경계에 공유 링크 무효화를 포함한다. 실제 정리를 완료한 경우만 200을 반환한다. 비동기가 필요해지면 202·상태 조회를 계약 변경으로 다룬다.

### 9. 회원 탈퇴 영향 조회 — `GET /api/users/me/withdrawal-impact`

**연결** — `user`의 본인 인증, `carespace/repository/CareSpaceQueryRepository.java`, `MembershipLifecycleService`의 참여·담당 업무 규칙.

- [ ] 현재 소유 공간을 정확하게 반환하고, 소유권 이전이나 삭제가 발생하면 기존 확인값이 무효화되는지 테스트한다.
- [ ] 본인에게만 소유 공간과 관련 영향 요약 및 `impactToken`을 반환한다. 탈퇴 전 본인 원본 삭제는 1~2단계의 기존 흐름으로 안내한다.

### 10. 회원 탈퇴 실행 — `POST /api/users/me/withdrawals`

**선행** — 8~9단계 및 기존 소유권 이전. `user/service/MemberIdentityService.java`, `user/repository/SocialAccountRepository.java`, `carespace/service/MembershipLifecycleService.java`, 기존 세션 종료 경계를 재사용한다.

- [ ] 소유 공간이 남으면 `OWNED_SPACE_REMAINS`로 차단한다. 재확인 중 소유권을 얻는 경쟁 상황, 중간 실패와 재시도, 탈퇴 후 기존 세션의 보호 API 접근 거부를 테스트한다.
- [ ] `impactToken`·`clientRequestId` 검증 후 멤버십과 미완료 담당 업무를 정리하고 공동 기록은 탈퇴한 사용자 표시로 유지한다. 계정·소셜 연결·세션 정리와 완료 응답의 순서를 결정한다. 탈퇴로 인증이 사라지는 만큼 응답 유실 시 FE는 결과 확인 필요 상태를 표시한다. 익명 결과 확인이 꼭 필요하면 열람 가능한 최소 정보와 보관 기간을 먼저 합의한다.

### 11. 공유 링크 생성 — `POST /api/spaces/{spaceId}/share-links`

**선행** — 2~3단계와 5·8단계의 무효화 경계. `careitem`의 현재 확정 항목 조회, 공간 참여 검증, 기존 반복 요청 처리를 사용한다.

- [ ] 현재 FE에 맞춰 `targets` 한 개를 권고한다. `expectedItemVersion`과 현재 상태를 검증하고 삭제·재확인·공간 불일치 항목을 거부한다. `includesChange`는 서버가 유효한 변경을 확인할 때만 허용한다.
- [ ] 추측하기 어려운 랜덤 토큰을 발급하고 원본 돌봄 내용을 공유 카드나 URL에 넣지 않는다. 토큰 만료와 키·저장 방식은 개발 전 명세에 반영한다. 재전송 시 링크·토큰 중복 발급을 막고 평문 토큰을 로그·일반 처리 기록에 넣지 않는 반환 방식을 같이 설계한다. 같은 링크를 재응답해야 한다면 전용 보호 저장을 사용한다.
- [ ] 11·12단계가 모두 배포되기 전 사용자 화면에 실제 발송을 노출하지 않는다. 카카오 SDK 호출과 공유 동작은 FE 작업이다.

### 12. 공유 링크 조회 — `GET /api/share-links/{token}`

**선행** — 11단계. 기존 세션 인증과 공간 참여, 항목 현재 버전 조회를 사용한다.

- [ ] 비로그인·비참여·제외된 구성원, 만료·무효 토큰, 삭제·재확인 항목을 검증한다. 처음 생성할 때의 원문이나 민감한 미리보기가 이전 링크·캐시에서 재노출되지 않아야 한다.
- [ ] 토큰을 식별자로만 사용하고 현재 참여자 권한과 항목 상태를 재검증한 뒤 현재 확인 가능한 정보만 반환한다. 첫 출시는 로그인한 현재 참여자의 열람과 전달을 권고한다. 공유 화면에서 답변을 받기로 하면 4단계와 연결하고 권한을 동일하게 적용한다.

### 13. 조건부 — `POST /api/spaces/{spaceId}/items/{itemId}/resolutions`

- [ ] 4단계 검증에서 기존 `request-applications`가 소유자 승인·해소를 처리하면 별도 경로는 도입하지 않는다. 별도 경로가 필요한 경우 같은 질문 해소 서비스와 동일 권한·검토 토큰·버전·잠금·반복 요청 규칙을 호출하도록 한다. 두 경로에서 같은 답변을 동시에 반영해도 결과는 한 번만 저장되는지 검증한다.

### 14. 조건부 — `GET /api/commands/{clientRequestId}`

- [ ] 각 변경 API에서 동일 ID 재전송과 최신 자원 조회로 처리 결과를 확인할 수 있는지 검증한다. 공통 조회가 꼭 필요한 사용자 흐름이 확인되면 `CommandReceiptRepository`의 저장 범위와 응답 수명부터 정한다. 기본 조회 범위는 요청자 본인 및 현재 대상 접근 권한과 안전한 ID·버전으로 한정한다. 다른 사용자의 ID 추측, 삭제 후 조회, 탈퇴 후 인증 상실을 테스트한다.

## 4. 연동 완료 판정

- [ ] 단계별 통합 테스트가 통과하고 기존 API의 응답·권한·충돌 처리에 회귀가 없다.
- [ ] 프론트의 실제 세션으로 소유자와 가족 각각 소식 작성 → 분석 → 제안 검토·반영 → 원본 삭제 확인·실행 → 재확인을 수행한다. 과거 URL과 목록·상세·근거·이력·공유 캐시에서도 삭제 내용이 다시 나타나지 않는다.
- [ ] 관련 화면의 로딩·빈 목록·권한 변경·버전 충돌·응답 유실을 Mock과 분리해 실제 API로 검증한다. 배포 검증 여부는 개발 완료와 따로 기록한다.
- [ ] 작업이 완료될 때 이슈·PR에는 코드와 검증 결과를 기록하고, 합의한 계약과 `백엔드 개발 현황`은 노션에 반영한다. 이 계획서는 세 Git 저장소 밖의 `HanMaEum` 폴더에 보관한다.
