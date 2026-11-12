"""Invented policy tests only. Never reads ASR, real speech or measured profiles.

Split base expressions BEFORE corruption; no variant crosses dev/held_out.
Held-out inventories are not imported by the production gate.
"""
import argparse
from copy import deepcopy
import hashlib
import json
from pathlib import Path
import random

from synth.degrade import DEFAULT_PROFILE, degrade

ROOT = Path(__file__).resolve().parents[1]

# Deliberately new combinations/paraphrases rather than copies of rule literals.
HELD_OUT_OPTOUT = [
    "오늘 대화는 녹취하지 말아 주세요", "우리 이야기 녹음하지는 말아 줘",
    "이번 내용은 기록하진 말자", "방금 한 말은 저장해 놓지 마",
    "저장은 하지 마", "녹취는 하지 말아 줘", "녹음을 중단해 주세요",
    "기록을 종료해 줘", "방금 했던 얘기를 전부 지워 줘", "직전 대화를 삭제해 주세요",
    "조금 전 말을 없애 줘", "방금 한 것은 지워 주세요", "우리 내용은 남기지 말자",
    "이번 이야기는 삭제해 줘", "지금 대화는 없애 줘", "이 얘기는 기록 목록에서 제외해",
    "이 대화는 녹음에서 빼 줘", "녹취 안 해 줬으면 좋겠어", "기록은 안 하면 좋겠어",
    "저장을 안 할래", "우리 말은 남기지 마세요", "방금 녹음을 다 지워 줘",
    "이 이야기는 저장하지 마세요", "녹취를 멈춰 줘"
]
COMMANDS = {
    "dev": ["에어컨 22도로", "히터 켜 줘", "공조 꺼 줘", "집으로 안내해 줘",
            "회사로 안내해 줘", "내비 켜 줘", "길 안내 종료해", "볼륨 올려",
            "볼륨 내려 줘", "음량 줄여 줘", "음악 틀어 줘", "라디오 꺼 줘",
            "다음 곡으로 넘겨 줘", "엄마한테 전화해 줘", "글레오", "글레오 노래 찾아 줘"],
    "held_out": ["공조 온도 21도로 맞춰 줘", "에어컨을 켜 주세요", "히터 온도 내려 줘",
                 "학교까지 길 안내해 줘", "서울역으로 경로 설정해 줘", "네비를 다시 시작해 줘",
                 "볼륨을 좀 높여 주세요", "음량 15로 맞춰 줘", "노래를 좀 틀어 주세요",
                 "미디어 일시 정지해 줘", "이전 곡으로 바꿔 줘", "통화를 종료해 줘",
                 "할머니에게 전화 걸어 줘", "아빠한테 전화해", "헤이 글레오 지금 날씨 알려 줘",
                 "헤이 플레오스 가까운 카페 찾아 줘"]
}
EXTERNAL = {
    "dev": ["300미터 앞에서 우회전입니다", "잠시 후 좌회전입니다", "이어서 직진입니다",
            "500미터 후 유턴하세요", "앞에 단속 구간입니다", "목적지에 도착했습니다",
            "청취자께서 보내 주신 사연을 소개합니다", "김민수님이 보내온 사연입니다",
            "박하나님의 신청곡입니다", "지금부터 이수진님의 사연을 소개합니다",
            "경로를 재탐색합니다", "안전 운전하세요"],
    "held_out": ["700미터 앞에서 좌회전하십시오", "2킬로미터 후 직진입니다",
                 "삼백미터 앞에서 우회전하시기 바랍니다", "잠시 후 유턴하십시오",
                 "제한 속도 80킬로미터 구간입니다", "이어서 우회전하세요",
                 "윤서연님께서 보내 주신 사연을 읽어드립니다", "청취자분이 보내온 사연이 도착했습니다",
                 "최유나님의 신청곡을 들려드립니다", "오늘은 정다은님의 사연입니다",
                 "다음은 청취자 강지호님의 사연을 읽어드립니다", "오백미터 후 좌회전입니다"]
}
# Family requests and discussions, including potentially misleading words.
NORMAL = [
    ["지우개 어디 있어", "필통 맨 아래 있어", "아 이 별 모양 지우개 기억나"],
    ["사진 저장했어?", "응 네가 웃는 사진도 저장했어", "그거 할머니께 보여 드리자"],
    ["불 꺼줘 엄마", "알았어 별 보려고 그러는구나", "저게 우리가 찾던 별이야"],
    ["칠판에 그린 그림 지워 줘", "조금만 더 보고 지울게", "내가 그린 토끼 귀엽지"],
    ["휴대폰 화면 꺼 줘 아빠", "그래 창밖 보자", "저 구름이 고래 같아"],
    ["내일 학교에서 녹음 수업 한대", "어떤 소리를 담고 싶어", "엄마 웃는 소리"],
    ["옛날 녹음 들으니까 네 목소리가 작더라", "그때는 아기였잖아", "참 많이 컸네"],
    ["녹음 파일 저장은 내가 했어", "노래 잘 불렀네", "같이 부른 부분이 좋아"],
    ["기록 경기에서 누나가 이겼어", "매일 연습한 보람 있네", "다음엔 나도 할래"],
    ["내비가 우회전이라고 했던 길 기억나", "거기 벚꽃 예뻤어", "다음 봄에도 가자"],
    ["300미터 앞에서 우회전이라고 알려 주더라", "그래서 골목 구경했지", "빵 냄새도 났고"],
    ["집으로 안내해 주는 내비가 편하긴 해", "옛날에는 지도 폈잖아", "엄마가 길 잘 찾았지"],
    ["볼륨 올려 놓으니까 북소리가 들리더라", "네가 배운 장단 같아", "내가 쳐 볼까"],
    ["엄마 에어컨 바람이 차가워", "담요 덮어 줄까", "응 포근하다"],
    ["라디오 사연에 나온 강아지가 귀엽더라", "우리 강아지도 그랬지", "신발 물고 도망갔어"],
    ["글레오라는 이름이 재미있어", "무슨 뜻일까", "우리 인형 이름도 지어 보자"],
    ["저장해 둔 동화 들려줄까", "응 토끼 이야기", "네가 결말 만들어 줬었지"],
    ["기록장에 엄마랑 소풍 간 날 썼어", "어떤 그림 그렸어", "커다란 도시락"],
    ["이 얼룩은 지워도 안 없어지네", "그때 딸기 먹다가 묻었지", "맛있었는데"],
    ["난로 꺼줘 아빠", "따뜻해졌구나", "응 고마워"],
    ["게임 저장하고 출발하자", "응 성을 다 지었어", "나중에 엄마도 보여 줘"],
    ["전화로 할머니랑 얘기했어", "뭐라고 하셨어", "보고 싶다고 하셨어"],
    ["지도를 보니까 집까지 멀다", "아빠 어릴 땐 걸어갔지", "무슨 놀이 하면서 갔어"],
    ["녹음기는 옛날에 어떻게 생겼어", "테이프 넣는 게 있었어", "다음에 보여 줘"],
    ["내비 안내 소리를 따라 해 봤어", "너무 똑같아서 웃었어", "이번엔 로봇 목소리야"],
    ["문 좀 닫아 줘 누나", "알았어", "우리 비밀 기지 같아"],
    ["이름 쓴 걸 지워도 될까", "응 새 이름 붙이자", "우리 배 이름은 무지개야"],
    ["노래 녹음은 끝났어", "처음엔 떨렸는데 재미있더라", "엄마도 같이해서 좋았어"],
    ["우리의 여행 기록은 그림책으로 만들자", "바다 페이지는 내가 그릴래", "그럼 산은 내가"],
    ["불 끄니까 별 스티커가 보여", "천장에 같이 붙였잖아", "매일 보니까 좋아"],
    ["에어컨 22도면 춥지 않을까", "아빠는 늘 춥대", "담요 왕자님이야"],
    ["할머니가 녹음해서 동화 보내 주셨어", "목소리 들으니 반갑다", "오늘 밤에 또 듣자"]
]
CONVERSATION = ["아까 만든 모래성이 정말 컸어", "엄마도 같이 만들었잖아",
                "조개로 문도 꾸몄지", "다음에는 할머니랑 만들자"]


def variants(text, seed):
    """Clean/spacing/coda probes + 8 unfiltered default Pleos degradations.

    Coda probe is an explicit synthetic stress, not a measured error profile.
    No retry, no discard, no relabel if degradation removes the policy cue.
    """
    rng = random.Random(seed)
    yield "clean", 0, [text]
    joined = "".join(c for c in text if c.isalnum())
    cut = max(1, len(joined) // 2)
    yield "compact_split", 0, [joined[:cut], joined[cut:]]
    hangul = [i for i, c in enumerate(joined) if "가" <= c <= "힣"]
    if hangul:
        i = rng.choice(hangul)
        c = joined[i]
        base = ord(c) - (ord(c) - 0xAC00) % 28
        coda = (ord(c) - base + 1) % 28
        damaged = joined[:i] + chr(base + coda) + joined[i + 1:]
    else:
        damaged = joined
    yield "coda", 1, [damaged]
    for level in (1, 2):
        for repeat in range(4):
            # Uses static DEFAULT_PROFILE only, never .local/demo/pleos.
            rows = degrade([dict(t=0, dur=4, spk="엄마", overlap=False, text=text)],
                           level=level, seed=seed + level * 100 + repeat,
                           style="pleos", profile=deepcopy(DEFAULT_PROFILE))
            yield f"pleos{level}_{repeat}", level, [r["text"] for r in rows]


def generate(seed=56):
    rules = json.loads((ROOT / "policy/rules.json").read_text(encoding="utf-8"))
    inventories = {"optout": {"dev": rules["optout_literals"], "held_out": HELD_OUT_OPTOUT},
                   "command": COMMANDS, "external": EXTERNAL}
    rows = []
    for category, splits in inventories.items():
        assert not set(splits["dev"]) & set(splits["held_out"])
        for split, expressions in splits.items():
            for index, expression in enumerate(expressions):
                base = f"{category}:{split}:{index}"
                local_seed = seed + int(hashlib.sha256(base.encode()).hexdigest()[:8], 16)
                for variant, level, lines in variants(expression, local_seed):
                    row = dict(id=f"{base}:{variant}", base_id=base, split=split,
                               category=category, variant=variant, level=level, lines=lines,
                               expected=dict(block="block", drop_lines=list(range(len(lines))),
                                             retro_delete=category == "optout"), synthetic=True)
                    rows.append(row)
                    if category == "command":
                        # Same expression/variants retain the base expression split.
                        mixed = CONVERSATION[:2] + lines + CONVERSATION[2:]
                        rows.append(dict(row, id=row["id"] + ":mixed", category="mixed",
                                         lines=mixed, expected=dict(block="allow", retro_delete=False,
                                             drop_lines=list(range(2, 2 + len(lines))))))
            # Multi-command/external bundles, using the same split inventory.
            if category in ("command", "external"):
                for index in range(0, len(expressions) - 1, 2):
                    lines = expressions[index:index + 2]
                    base = f"{category}:{split}:pair{index}"
                    rows.append(dict(id=base, base_id=base, split=split, category=category,
                                     variant="pair", level=0, lines=lines, synthetic=True,
                                     expected=dict(block="block", drop_lines=[0, 1], retro_delete=False)))
    for index, conversation in enumerate(NORMAL):
        for variant, level, lines in variants(" ".join(conversation), seed + index):
            # Clean cases keep original family turn boundaries.
            if variant == "clean":
                lines = conversation[:]
            rows.append(dict(id=f"normal:{index}:{variant}", base_id=f"normal:{index}",
                             split="challenge", category="normal_challenge", variant=variant,
                             level=level, lines=lines, synthetic=True,
                             expected=dict(block="allow", drop_lines=[], retro_delete=False)))
    return rows


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--seed", type=int, default=56)
    parser.add_argument("--out", type=Path, default=Path(".local/policy/cases.jsonl"))
    args = parser.parse_args(argv)
    rows = generate(args.seed)
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text("".join(json.dumps(r, ensure_ascii=False) + "\n" for r in rows), encoding="utf-8")
    print(json.dumps(dict(synthetic_cases=len(rows), seed=args.seed)))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
