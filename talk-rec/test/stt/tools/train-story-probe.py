"""Build the tiny Korean fixture classifier using only the standard library."""

from collections import Counter, defaultdict
from math import exp, sqrt
from pathlib import Path
from random import Random
import unicodedata


def examples(label, lines):
    return [(label, line) for line in lines.strip().splitlines()]


TRAIN = examples(1, """
그때 우리 다 같이 웃다가 눈물이 났잖아
아빠 모자가 바람에 날아가서 모두 웃었어요
그날 처음 바다를 보고 엄청 행복했어
할머니가 내 손을 꼭 잡았던 게 아직도 기억나
길을 잘못 들어서 뜻밖에 예쁜 호수를 발견했어
엄마가 깜짝 선물을 꺼냈을 때 정말 놀랐어
우리 둘이 처음 만나던 날 기억해?
그날 노을이 너무 예뻐서 한참 바라봤어
동생이 넘어지고 자기도 웃어서 우리도 빵 터졌어
아빠가 길을 잃었는데 결국 가장 좋은 식당을 찾았어
그때 할아버지가 불러준 노래를 아직 기억해
비를 맞으면서도 계속 걸었던 그날을 잊을 수 없어
친구가 엉뚱한 농담을 해서 차 안이 웃음바다가 됐어
우리 처음 눈을 봤던 날 둘이 사진도 찍었잖아
고장 난 우산을 같이 쓰고 돌아오던 게 생각나
강아지가 물에 뛰어들어서 우리가 다 놀랐잖아
그때 동생이 내 손을 꼭 잡아서 마음이 따뜻했어
텐트가 날아갔는데 서로 얼굴 보고 한참 웃었어
처음 혼자 운전한 날 아빠가 잘했다고 해 줬어
그 여행에서 다 같이 본 별이 아직도 기억나요
""") + examples(0, """
다음 휴게소까지 얼마나 걸려요
가까운 주유소를 찾아 줘
목적지에 도착하면 알려 줘
오늘 저녁 메뉴 뭐 먹을까
글레오 내비게이션을 켜 줘
글레오 웃긴 노래 틀어 줘
창문 조금만 내려 주세요
고속도로 통행료가 얼마인가요
처음 가는 길인데 다음 교차로에서 어디로 가요
사진 한 장 찍어 주세요
오늘 노을 지는 시간이 몇 시야
할머니한테 도착 시간을 알려 드려
주차장에서 우회전하세요
이 휴게소에서 화장실 어디에 있어요
물병을 내 쪽으로 주세요
에어컨 온도를 이십 도로 바꿔 줘
우리 오늘 출발할 때 몇 시였어
아빠한테 전화 걸어 줘
저녁에 어느 식당으로 갈까요
이 앞 교통 상황을 확인해 줘
""")

HOLDOUT = examples(1, """
그때 고양이가 텐트에 들어와서 모두 웃었잖아
처음 캠핑 갔을 때 우리가 새벽까지 이야기한 거 기억나
친구가 생일 케이크를 몰래 가져와서 정말 놀랐어
엄마가 노을을 보면서 내 손을 꼭 잡았던 게 떠올라
아빠랑 길을 헤매다가 처음 바다를 봤는데 신기했어
그 여행에서 잃어버린 모자를 강아지가 가져왔어
할머니가 해 주신 이야기에 웃다가 배가 아팠어
다 같이 비 맞으면서 춤추던 날 잊지 못해
""") + examples(0, """
다음 휴게소까지 몇 킬로 남았어
글레오 바다 근처 주차장 찾아 줘
지금 웃긴 영상 제목을 검색해 줘
처음 가는 곳이라 출발 시간을 확인해 줘
오늘 찍은 사진을 보내 줘
엄마에게 몇 시에 도착한다고 문자 보내 줘
저녁에 바다가 보이는 식당 있나요
노을 보러 가려면 몇 시에 출발해야 해
""")


def features(text):
    text = unicodedata.normalize("NFKC", text[:512]).lower()
    chars = "".join(c if c.isalnum() else " " for c in text)
    normalized = "_" + "_".join(chars.split()) + "_"
    return {
        normalized[i : i + n]
        for n in (2, 3, 4)
        for i in range(len(normalized) - n + 1)
    }


def main():
    samples = [(label, features(text)) for label, text in TRAIN]
    counts = Counter(feature for _, sample in samples for feature in sample)
    samples = [(label, sorted(f for f in sample if counts[f] >= 2)) for label, sample in samples]
    weights = defaultdict(float)
    bias = 0.0
    rng = Random(42)
    for _ in range(200):
        rng.shuffle(samples)
        for label, sample in samples:
            scale = sqrt(max(1, len(sample)))
            score = bias + sum(weights[f] for f in sample) / scale
            step = 0.12 * (label - 1 / (1 + exp(-score)))
            bias += step
            for f in sample:
                weights[f] += step / scale

    assets = Path(__file__).resolve().parents[1] / "src/main/assets/feature2"
    assets.mkdir(parents=True, exist_ok=True)
    model = [f"bias\t{bias:.6f}"] + [
        f"{feature}\t{value:.6f}" for feature, value in sorted(weights.items())
    ]
    (assets / "story-model.tsv").write_text("\n".join(model) + "\n", encoding="utf-8", newline="\n")
    fixture = "\n".join(f"{label}\t{text}" for label, text in HOLDOUT)
    (assets / "story-holdout.tsv").write_text(fixture + "\n", encoding="utf-8", newline="\n")
    print(f"{len(TRAIN)} training examples, {len(HOLDOUT)} holdout examples, {len(weights)} weights")


if __name__ == "__main__":
    main()
