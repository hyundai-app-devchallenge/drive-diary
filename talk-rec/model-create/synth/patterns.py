"""Invented topic inventory. No reference-video transcripts or named brands."""
LABELS = ('story', 'moment', 'none')
DEFINITIONS = {
    'story': '누군가 화제를 꺼내고 다른 사람이 받아서 두세 번 이상 오간 흐름. 완결될 필요 없음.',
    'moment': '이어지지 않아도 남길 만한 한마디·웃음·함께 부르는 노래·감탄·장난.',
    'none': '그 외.',
}

# id, label, one-line direction, topic group
PATTERNS = [
    ('parent_interview', 'story', '부모의 질문에 아이가 길게 두서없이 답하고 말 고치기와 딴 얘기가 샌다', 'life'),
    ('kid_recount', 'story', '아이가 겪은 일을 길게 늘어놓고 가족이 받아주거나 형제가 사실을 고친다', 'life'),
    ('shared_memory', 'story', '같이 겪은 일을 서로 보태고 기억을 다르게 말한다', 'memory'),
    ('choice_game', 'story', '둘 중 하나 고르기를 이어가며 서로 이유를 설명한다', 'choice'),
    ('interrupted_story', 'story', '용무나 운전 주의가 끼어들고 화제로 돌아가거나 중간에 끊긴다', 'life'),
    ('grandparent_recall', 'story', '조부모의 옛일에 가족이 끼어들고 세대가 다른 경험을 주고받는다', 'memory'),
    ('after_none', 'story', '처음에는 용무와 내비뿐이다가 뒤쪽에서 짧은 이야기가 두세 번 오가기 시작한다', 'life'),
    ('adult_imitation', 'moment', '아이가 어른이나 설교 말투 또는 짧은 영어를 흉내내고 가족이 웃는다', 'play'),
    ('tease_reply', 'moment', '짧은 놀림과 엉뚱한 받아치기 한 번으로 웃는다', 'play'),
    ('chant', 'moment', '구호 같은 말을 같이 몇 번 외치지만 이야기는 이어지지 않는다', 'play'),
    ('song', 'moment', '즉석에서 지은 짧은 노래 한 소절을 같이 부르다 그친다', 'song'),
    ('novice_reaction', 'moment', '초보 운전을 겪는 가족이 짧고 인상적인 감상을 내뱉는다', 'view'),
    ('unanswered_moment', 'moment', '질문에 답은 없지만 질문 자체나 옆의 한마디가 웃음을 남긴다', 'play'),
    ('moment_in_errands', 'moment', '긴 길 안내·용무 흐름 한가운데 남길 만한 한마디에 웃고 다시 용무로 돌아간다', 'play'),
    ('route_parking', 'none', '처음 가는 곳의 길과 주차장 입구를 여러 번 확인하며 길게 주고받는다', 'driving'),
    ('adult_planning', 'none', '어른끼리 일정·장보기·돈 이체를 길게 의논하지만 지금 처리할 용무뿐이다', 'planning'),
    ('phone_work', 'none', '스피커폰으로 업무 일정·서류·확인을 주고받는다. 통화상대 화자를 허용하고 아이는 용무나 딴소리만 한다', 'work'),
    ('sibling_squabble', 'none', '형제가 자리·간식·창문으로 실랑이하고 어른이 말린다. 회상·놀이·남길 한마디 없이 용무로 반복된다', 'errand'),
    ('service_steps', 'none', '주유·세차·계산 순서와 영수증을 계속 확인한다. 아이가 보채도 화제가 서지 않는다', 'service'),
    ('driving_only', 'none', '길고 여러 번 오가도 방향·속도·주차 지시뿐이다', 'driving'),
    ('navigation', 'none', '내비 안내와 짧은 확인만 이어진다', 'driving'),
    ('errand', 'none', '물건을 찾거나 닦거나 건네는 용무뿐이다', 'errand'),
    ('fragments', 'none', '앞뒤 맥락 없는 조각 한두 줄과 긴 침묵', 'errand'),
    ('greeting', 'none', '영상 시작·끝 인사말과 준비 말뿐이다', 'errand'),
    ('short_answers', 'none', '말은 오가지만 서로 무관한 단답만 나열한다', 'errand'),
    ('unanswered', 'none', '평범한 질문 뒤 대답이 없고 용무로 끊겨 화제가 서지 않는다', 'life'),
]
BY_ID = {p[0]: dict(id=p[0], label=p[1], description=p[2], group=p[3]) for p in PATTERNS}
BOUNDARY_PATTERNS = {'none': ('driving_only', 'unanswered', 'route_parking', 'adult_planning',
                             'phone_work', 'sibling_squabble', 'service_steps'),
                     'moment': ('unanswered_moment', 'moment_in_errands'), 'story': ('after_none',)}
# Old quiet patterns stay in the inventory, at a lower sampling weight.
QUIET_NONE = {'fragments', 'greeting', 'short_answers', 'unanswered'}
CHILD_PATTERNS = {'parent_interview', 'kid_recount', 'adult_imitation', 'sibling_squabble'}
TOPICS = {
    'life': ['학교 모둠 만들기', '친구와 자리 바꾸기', '급식 반찬 고르기', '학원 숙제 빼먹기',
             '게임에서 길 잃기', '만화 속 동물', '반려동물 잠버릇', '먹고 싶은 간식',
             '장래 희망 바뀐 이유', '형제 장난감 다툼', '준비물 놓고 온 날', '체육 시간 공놀이',
             '발표하다 말 잊은 일', '쉬는 시간 종이접기', '도서관 책 반납', '유치원 낮잠',
             '비 맞은 운동화', '친구에게 빌린 지우개', '가족끼리 정한 별명', '자전거 처음 탄 날'],
    'memory': ['여행 때 비 온 날', '바닷가 모래성', '산책하다 본 강아지', '소풍 도시락',
               '휴게소에서 흘린 국물', '조부모 어린 시절 통학', '옛날 겨울 난방', '처음 만든 주먹밥',
               '길 잘못 든 나들이', '시장 구경', '사촌과 숨바꼭질', '밤에 본 별'],
    'choice': ['버스와 택시', '피자와 햄버거', '전기차와 휘발유차', '바다와 산',
               '비 오는 날과 눈 오는 날', '날기와 투명해지기', '강아지와 고양이', '아침밥과 늦잠',
               '기차와 배', '그림 그리기와 만들기'],
    'play': ['어른의 전화 말투', '정리하라는 잔소리 흉내', '엉터리 영어 인사', '과장된 출발 구호',
             '졸린 사람 놀리기', '큰 모자 별명', '배고픔 선언', '길쭉한 구름 감탄',
             '양말 짝짝이 발견', '우산 접는 척', '아는 척하는 말투', '장난스러운 칭찬'],
    'song': ['창밖 나무 즉석 노래', '빗방울 즉석 노래', '졸음 쫓는 콧노래', '간식 기다리는 노래'],
    'view': ['느린 주차 한마디', '출발 전 긴장', '커브 돌 때 감탄', '조심스러운 브레이크'],
    'driving': ['우회전 폭', '깜빡이 확인', '직진 반복', '후진 주차', '차선 바꾸기',
                '속도 줄이기', '앞차 간격', '내비 거리 안내', '단속 구간 안내', '고속도로 출구'],
    'errand': ['휴지 찾기', '물병 건네기', '창문 닦기', '쓰레기 봉투', '안전띠 확인',
               '가방 위치', '신발 정리', '충전선 찾기', '영상 시작 인사', '영상 끝 인사',
               '에어컨 온도', '문 닫기 확인'],
    'planning': ['이번 주 픽업 일정', '장보기 목록 수정', '학원비 이체 확인', '관리비 납부',
                 '병원 예약 시간', '택배 수령 순서', '식재료 예산', '주말 이동 시간',
                 '생활비 계좌 잔액', '카드 결제일', '반품 접수', '세탁물 찾기'],
    'work': ['서류 전달 시간', '회의실 예약 확인', '작업 순서 확인', '견적서 금액 수정',
             '납품 수량 확인', '근무 교대 시간', '접수 번호 확인', '일정표 수정',
             '메일 첨부 확인', '방문 예약', '영수증 발급', '재고 수량 확인'],
    'service': ['주유 금액 선택', '주유구 방향 확인', '카드 결제 순서', '영수증 출력',
                '세차 대기 줄', '세차 코스 선택', '창문 닫기 절차', '진공청소기 사용 순서',
                '주차비 정산', '할인권 입력', '충전 결제 확인', '매트 세척 순서'],
}
SITUATIONS = ['등원·등교길', '하원·하교길', '주말 나들이 가는 길', '귀가길', '장거리 고속도로',
              '마트 가는 길', '병원 가는 길', '운전 연습', '명절 이동', '비 오는 날 정체',
              '아침 출근 동승', '야간 이동', '주차장 진입', '조부모 댁 가는 길']
INTERRUPTIONS = ['운전 지시', '내비 음성', '용무', '아이 딴소리', '침묵']
