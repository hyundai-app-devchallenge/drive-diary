"""Probe Workers AI using only an invented Korean dialogue, never real recordings."""

import argparse
import json
import re

from tools.cloudflare_ai import CloudflareBackend, CloudflareError, DEFAULT_MODEL, extract_json
from tools.summary_relay import STORY_PROMPT_PATH, output_schema, prompt_categories, valid_model, validate_split_output

# This constant is the only probe input; there is deliberately no file/stdin option.
INVENTED_DIALOGUE = ('[1]\n아빠, 차 창밖 구름이 종이배 같아.\n'
                      '그러네. 어제 같이 접은 배도 기억나?\n응, 내 배에는 돛도 달았잖아!')


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--model', default=DEFAULT_MODEL)
    parser.add_argument('--variant', choices=('default', 'no-thinking'), default='default',
                        help='default: omit chat_template_kwargs; no-thinking: enable_thinking=false (experimental)')
    parser.add_argument('--show', action='store_true', help='show generated content for the invented input only')
    args = parser.parse_args(argv)
    if not valid_model(args.model) or not args.model.startswith('@cf/'):
        parser.error('--model must be a valid @cf/ model name')
    report = dict(http_status=None, response_format_accepted=None, finish_reason=None,
                  reasoning_tokens=None, latency_ms=None, has_think_or_prefix=False,
                  content_length=0, validation='fail', error_code=None)
    content = None
    try:
        system = STORY_PROMPT_PATH.read_text(encoding='utf-8')
        categories = prompt_categories(system)
        kwargs = {'enable_thinking': False} if args.variant == 'no-thinking' else None
        result = CloudflareBackend(model=args.model, json_schema=output_schema(categories, segment_count=1),
                                   chat_template_kwargs=kwargs).complete(system, INVENTED_DIALOGUE)
        content = result.content
        brace = content.find('{')
        report.update(http_status=result.http_status, response_format_accepted=result.response_format_accepted,
                      finish_reason=result.finish_reason, reasoning_tokens=result.reasoning_tokens,
                      latency_ms=result.latency_ms, content_length=len(content),
                      has_think_or_prefix=bool(re.search(r'<think\b', content, re.I)
                                               or (brace > 0 and content[:brace].strip())))
        validate_split_output(extract_json(content), categories, 1)
        report.update(validation='pass')
    except CloudflareError as error:
        report.update(http_status=error.http_status, response_format_accepted=error.response_format_accepted,
                      latency_ms=error.latency_ms, error_code=error.code)
    except ValueError:
        report['error_code'] = 'invalid_output'
    except Exception:
        report['error_code'] = 'backend_error'
    print(json.dumps(report, ensure_ascii=True, allow_nan=False))
    if args.show and content is not None:
        print(content)
    return 0 if report['validation'] == 'pass' else 2


if __name__ == '__main__':
    raise SystemExit(main())
