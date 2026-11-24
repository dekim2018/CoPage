/**
 * 부하 도구 (docs/11-TEST-PLAN.md 13.3, 진단 4단계). 실제 서버에 가상 편집자를 붙여 ack 지연, 다른 사람 화면 반영 지연, 거부와
 * 끊김, 수렴을 잰다. 가상 편집자는 브라우저 클라이언트와 같은 상태 기계(ClientSession)와 같은 보내기 간격(50ms)을 쓴다.
 */
package com.copage.loadgen;
