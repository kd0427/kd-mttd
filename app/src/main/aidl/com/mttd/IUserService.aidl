// IUserService.aidl
//
// Shizuku UserService 인터페이스. 이 프로세스는 shell UID(2000) 로 실행되며
// 앱 프로세스가 이 AIDL 을 통해 좁은 op 만 요청할 수 있음.
//
// 원칙 (보안):
// - shell 커맨드 문자열을 파라미터로 받지 않는다. 모든 op 는 이름과 타입이 고정.
// - 파일 경로는 UserService 구현체 안에서 whitelist 검사 후 처리.
// - 새로운 op 추가 시 그 이유를 커밋 메시지에 명시.

package com.mttd;

interface IUserService {
    /**
     * `stat -c %s <path>` 등가. 파일 크기(바이트) 반환.
     * 파일이 없거나 접근 불가면 -1.
     */
    long getFileSize(String path) = 1;

    /** 존재 여부만 확인. */
    boolean fileExists(String path) = 2;

    /**
     * `tail -c +offset <path> | head -c maxBytes` 등가.
     * 내부적으로 `RandomAccessFile.seek(offset).read(...)` — 셸 spawn 회피.
     *
     * @param path      whitelist 통과한 파일 경로.
     * @param offset    바이트 오프셋.
     * @param maxBytes  최대 요청 크기. 서버가 min(maxBytes, MAX_CHUNK) 로 제한(현재 262144).
     * @return          읽은 바이트. 빈 배열이면 EOF 또는 offset >= size.
     */
    byte[] readFileChunk(String path, long offset, int maxBytes) = 3;

    /**
     * 설치된 패키지 이름 목록 (개행 구분).
     * 게임 실행 여부/설치 여부 감지용. 화이트리스트에 매치되는 것만 필터해서 반환.
     */
    String listInstalledGamePackages() = 4;

    // NOTE: 로그 삭제 op(=5)는 한 번 넣었다가 제거했다.
    // 게임이 ~330 MB 에서 스스로 회전(_BK)시켜 총 2 개로 상한이 걸리므로 정리할 필요가 없고,
    // 그 하나를 위해 읽기 전용이던 이 인터페이스에 파괴적 op 을 두는 건 대가가 크다.
    // 번호 5 는 재사용하지 않는다.

    /** Shizuku 표준 destroy 메서드 (transaction code 16777114 예약됨). */
    void destroy() = 16777114;
}
