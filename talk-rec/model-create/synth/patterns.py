"""Invented topic inventory. No reference-video transcripts or named brands."""
LABELS = ('story', 'moment', 'none')
DEFINITIONS = {
    'story': '누군가 화제를 꺼내고 다른 사람이 받아서 두세 번 이상 오간 흐름. 완결될 필요 없음.',
    'moment': '이어지지 않아도 남길 만한 한마디·웃음·함께 부르는 노래·감탄·장난.',
    'none': '남길 화제·순간 없는 용무 또는 현재 분위기가 부정적인 대화. 길고 여러 번 오가도 없음.',
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
    ('phone_work', 'none', '가족인 통화상대와 스피커폰으로 일정·서류·확인 용무만 주고받는다', 'work'),
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
NEGATIVE_PATTERNS = {'argument', 'scolding', 'tantrum', 'complaint', 'worrying_news'}
PATTERNS += [
    ('argument', 'none', '가족이 서로 탓하며 진짜로 다툰다. 여러 번 반박하고 언성이 높아지며 밝은 화해로 바꾸지 않는다', 'life'),
    ('scolding', 'none', '어른이 아이를 거듭 혼내고 아이가 대들거나 운다. 여러 번 오가도 현재 분위기가 부정적이다', 'life'),
    ('tantrum', 'none', '아이가 울고 떼쓰며 어른이 지쳐 말린다. 반복되는 요구와 응답이 장면의 중심이다', 'life'),
    ('complaint', 'none', '가족이 친척·이웃에 대한 험담과 불평을 여러 번 보탠다. 즐거운 장난으로 쓰지 않는다', 'life'),
    ('worrying_news', 'none', '가족이 안 좋은 소식과 걱정을 무겁게 여러 번 나눈다. 해결이나 교훈으로 마무리하지 않는다', 'life'),
    ('warm_recount', 'story', '지나간 속상한 일이나 그리운 추억을 가족이 따뜻하게 받아 주며 여러 번 나눈다. 현재는 다투지 않는다', 'memory'),
]
BY_ID = {p[0]: dict(id=p[0], label=p[1], description=p[2], group=p[3]) for p in PATTERNS}
BOUNDARY_PATTERNS = {'none': ('driving_only', 'unanswered', 'route_parking', 'adult_planning',
                             'phone_work', 'sibling_squabble', 'service_steps'),
                     'moment': ('unanswered_moment', 'moment_in_errands'), 'story': ('after_none',)}
# Old quiet patterns stay in the inventory, at a lower sampling weight.
QUIET_NONE = {'fragments', 'greeting', 'short_answers', 'unanswered'}
CHILD_PATTERNS = {'parent_interview', 'kid_recount', 'adult_imitation', 'sibling_squabble'}
CHILD_PATTERNS |= {'scolding', 'tantrum'}
SITUATIONS = ['등원·등교길', '하원·하교길', '주말 나들이 가는 길', '귀가길', '장거리 고속도로',
              '마트 가는 길', '병원 가는 길', '운전 연습', '명절 이동', '비 오는 날 정체',
              '아침 출근 동승', '야간 이동', '주차장 진입', '조부모 댁 가는 길']
INTERRUPTIONS = ['운전 지시', '내비 음성', '용무', '아이 딴소리', '침묵']

# Neutral seeds: the pattern determines how the same material is used. No
# label-specific topic inventories. Old groups remain aliases for consumers.
TOPIC_DOMAINS = {
    '나들이': ['여행 숙소', '바닷가', '숲길', '기차 나들이', '동네 공원', '캠핑 자리', '소풍 도시락', '전망대', '박물관', '계곡'],
    '생활': ['장보기', '가족 달력', '빨래', '우산', '도시락 통', '책장', '화분', '물병', '집 열쇠', '이사 상자'],
    '배움': ['종이접기', '그림', '숫자 놀이', '블록', '동물 그림책', '준비물', '학교 행사', '발표', '줄넘기', '모래 놀이'],
    '관계': ['형제 자리', '사촌 방문', '조부모 편지', '가족 사진', '이웃 인사', '생일 준비', '빌린 물건', '가족 약속', '함께 만든 간식', '오래된 담요'],
    '이동': ['골목길', '주차 자리', '갈림길', '휴게소', '버스 정류장', '자동차 창문', '가방 위치', '안전띠', '차 안 온도', '고속도로 출구'],
    '돌봄': ['아기 신발', '낮잠', '진료 예약', '가족 안부', '병문안', '용돈', '잃어버린 장갑', '깨진 컵', '비 맞은 운동화', '반려동물'],
}
TOPIC_GROUP = {f'{subject} {detail}': group for group, subjects in TOPIC_DOMAINS.items()
               for subject in subjects for detail in ('준비', '기억', '선택', '변화', '기다림', '약속')}
SHARED_TOPICS = list(TOPIC_GROUP)
TOPICS = {group: SHARED_TOPICS for group in {p['group'] for p in BY_ID.values()}}
SITUATIONS += ['도서관에서 돌아오는 길', '공원 산책 뒤 이동', '소풍 준비하러 가는 길',
               '이사한 집으로 가는 길', '가족 생일 모임 뒤', '해 질 무렵 강변길',
               '눈 온 다음 날 이동', '이른 새벽 출발', '터널을 지난 뒤', '비 그친 동네길']
STYLES = ['짧게 끊는 말', '천천히 되묻는 말', '낱말을 고쳐 말하기', '작게 중얼대기',
          '말끝 흐리기', '못 들어서 다시 묻기', '사투리 없이 편한 구어', '어순 뒤집기',
          '말 사이 뜸 들이기', '같은 말 다시 꺼내기', '손짓을 가리키는 말', '겹쳐 말하다 멈추기']
