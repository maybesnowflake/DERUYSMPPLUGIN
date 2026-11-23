# Studio 검증판에 이 저장소 연결하기

검증판: https://fsixel-studio-board.mincraftsnowflake.workers.dev
검증판 쪽 허용 목록은 sandbox PR(`board: DERUYSMPPLUGIN 저장소 허용`) 병합·배포로 처리됩니다. 아래는 비밀값·계정 설정이라 **직접** 해야 합니다.

| # | 할 일 | 어디서 |
|---|---|---|
| 1 | sandbox PR 병합 후 Actions → `deploy-board` 실행 (Claude가 Codex 리뷰 통과 후 처리) | fsixel-studio-sandbox |
| 2 | 루틴에 저장소 추가: 검증판이 쓰는 API 트리거 루틴, `studio:fix` 레이블 GitHub 트리거 루틴 → `maybesnowflake/DERUYSMPPLUGIN` 추가 (레이블 트리거는 이 저장소용으로 하나 더) | claude.ai/code/routines |
| 3 | `STUDIO_GITHUB_TOKEN`으로 쓰는 Fine-grained 토큰 → Repository access 에 DERUYSMPPLUGIN 추가 (Pull requests 읽기/쓰기, Issues 읽기/쓰기) | GitHub → Settings → Developer settings → Fine-grained tokens |
| 4 | 웹훅: URL `https://fsixel-studio-board.mincraftsnowflake.workers.dev/webhooks/github`, Content type `application/json`, Secret = sandbox 의 `STUDIO_WEBHOOK_SECRET` 과 같은 값, 이벤트: Pull requests · Pull request reviews · Issue comments · Workflow runs | DERUYSMPPLUGIN → Settings → Webhooks |
| 5 | Codex 코드 리뷰가 이 저장소에서 켜져 있는지 확인 (이미 PR #5에서 동작함) | chatgpt.com/codex/cloud/settings |
| 6 | 검증판에서 대상 저장소 `maybesnowflake/DERUYSMPPLUGIN` 으로 작은 작업 하나 만들어 시험 | 검증판 |

토큰 값은 바뀌지 않으므로 Cloudflare Secrets 는 그대로 두면 됩니다.
