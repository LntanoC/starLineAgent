"""Bounded, read-only crawler feasibility probe; not a production collector.

Python 3.12 stdlib; --pdf additionally requires pypdf. Historical Guangzhou
sample is intentional: this command validates extraction, not upcoming events.
"""
import argparse
import io
import json
import re
import time
from datetime import datetime, timedelta, timezone
from html.parser import HTMLParser
from pathlib import Path
from urllib.parse import urlencode, urljoin, urlsplit
from urllib.request import HTTPRedirectHandler, Request, build_opener


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


class Page(HTMLParser):
    def __init__(self, html):
        super().__init__(convert_charrefs=True)
        self.links, self.rows = [], []
        self.anchor, self.row, self.cell = None, None, None
        self.feed(html)

    def handle_starttag(self, tag, attrs):
        attrs = dict(attrs)
        if tag == 'a':
            self.anchor = [attrs.get('href', ''), []]
        elif tag == 'tr':
            self.row = []
        elif tag in ('td', 'th') and self.row is not None:
            self.cell = []

    def handle_data(self, text):
        if self.anchor is not None:
            self.anchor[1].append(text)
        if self.cell is not None:
            self.cell.append(text)

    def handle_endtag(self, tag):
        if tag == 'a' and self.anchor is not None:
            self.links.append((self.anchor[0], clean(' '.join(self.anchor[1]))))
            self.anchor = None
        elif tag in ('td', 'th') and self.cell is not None:
            self.row.append(clean(' '.join(self.cell)))
            self.cell = None
        elif tag == 'tr' and self.row is not None:
            self.rows.append(self.row)
            self.row, self.cell = None, None


def clean(value):
    return re.sub(r'\s+', ' ', value).strip()


class Probe:
    hosts = {'banshi.whlyj.beijing.gov.cn', 'wglj.gz.gov.cn', 'search.damai.cn'}

    def __init__(self):
        self.opener = build_opener(NoRedirect())
        self.last = 0
        self.count = 0

    def fetch(self, url):
        parsed = urlsplit(url)
        if parsed.scheme != 'https' or parsed.hostname not in self.hosts or parsed.port not in (None, 443) or parsed.username:
            raise ValueError('URL is outside the fixed HTTPS source allowlist')
        time.sleep(max(0, .8 - (time.monotonic() - self.last)))
        self.last = time.monotonic()
        self.count += 1
        req = Request(url, headers={'User-Agent': 'StarLineAgent-FeasibilityProbe/1.0'})
        with self.opener.open(req, timeout=15) as response:
            body = response.read(5_000_001)
            if len(body) > 5_000_000:
                raise ValueError('Response exceeds 5 MB probe limit')
            return body, response.headers.get_content_type(), response.headers.get_content_charset() or 'utf-8'

    def html(self, url):
        body, kind, charset = self.fetch(url)
        if kind not in ('text/html', 'application/xhtml+xml'):
            raise ValueError('Expected HTML, got ' + kind)
        return body.decode(charset, errors='replace')


def beijing(probe, artist, pdf):
    url = 'https://banshi.whlyj.beijing.gov.cn/cms/web/whj/index.jsp?' + urlencode({'name': artist, 'curPage': 1})
    page = Page(probe.html(url))
    candidates = list(dict.fromkeys((urljoin(url, href), label) for href, label in page.links
                                   if 'content.jsp?id=' in href and artist in label))
    records = []
    for detail, title in candidates[:2]:
        item = {'title': title, 'source_url': detail, 'stage': 'APPROVAL_CANDIDATE', 'event_dates': None, 'attachments': []}
        try:
            for href, label in Page(probe.html(detail)).links:
                attachment = urljoin(detail, href)
                if urlsplit(attachment).path.lower().endswith('.pdf'):
                    fact = {'url': attachment, 'label': label, 'pdf_text_checked': False}
                    item['attachments'].append(fact)
                    if pdf:
                        try:
                            from pypdf import PdfReader
                            body, _, _ = probe.fetch(attachment)
                            if not body.startswith(b'%PDF-'):
                                raise ValueError('Attachment is not a PDF')
                            reader = PdfReader(io.BytesIO(body))
                            # Bounded text-layer check; no inferred dates or personal data persisted.
                            text = '\n'.join(p.extract_text() or '' for p in reader.pages[:3])
                            fact.update(pdf_text_checked=True, pages=len(reader.pages),
                                        text_layer_present=bool(text.strip()), artist_in_text=artist in text)
                        except Exception as error:
                            fact['error'] = type(error).__name__ + ': ' + str(error)
        except Exception as error:
            item['detail_error'] = type(error).__name__ + ': ' + str(error)
        records.append(item)
    return {'status': 'PARTIAL' if records else 'NO_MATCH_ON_TEST_PAGE', 'url': url,
            'candidate_count': len(candidates), 'records': records,
            'limitations': ['Only page 1 and at most 2 details checked', 'PDF fields and approval changes not normalized']}


def guangzhou(probe, artist):
    url = 'https://wglj.gz.gov.cn/xxgk/bmwj/ywxx/wgl/content/post_10729254.html'
    rows = Page(probe.html(url)).rows
    header = next((r for r in rows if '许可编号' in r and '演出时间' in r), None)
    if header is None:
        raise ValueError('Expected approval table header missing; possible page structure change')
    records = []
    for row in rows:
        if artist in ' '.join(row):
            if len(row) != len(header):
                raise ValueError('Table row/header length mismatch')
            records.append({'source_url': url, 'stage': 'APPROVED', 'fields': dict(zip(header, row)),
                            'dates_are_approval_range': True, 'can_buy_ticket': None})
    return {'status': 'OK' if records else 'NO_MATCH_IN_SAMPLE', 'historical_sample': True, 'records': records,
            'limitations': ['One fixed historical notice; no list pagination or upcoming coverage tested']}


def damai(probe, artist):
    url = 'https://search.damai.cn/searchajax.html?' + urlencode({'keyword': artist, 'currPage': 1, 'pageSize': 10})
    body, kind, charset = probe.fetch(url)
    text = body.decode(charset, errors='replace')
    if any(marker in text.lower() for marker in ('x5sec', 'captcha', '_____tmd_____', 'rgv587')):
        return {'status': 'BLOCKED_CHALLENGE', 'content_type': kind, 'records': None,
                'note': 'Challenge response; not an empty event result. No retries or bypass attempted.'}
    try:
        data = json.loads(text)
    except ValueError:
        return {'status': 'UNEXPECTED_RESPONSE', 'content_type': kind, 'records': None}
    return {'status': 'JSON_RECEIVED_UNVALIDATED', 'content_type': kind,
            'top_level_keys': list(data) if isinstance(data, dict) else [], 'records': None,
            'note': 'JSON alone does not establish a valid event-list schema or permission.'}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--artist', default='薛之谦')
    parser.add_argument('--pdf', action='store_true', help='Check the first 3 pages of each PDF text layer with pypdf')
    parser.add_argument('--out', type=Path, required=True)
    args = parser.parse_args()
    probe = Probe()
    result = {'checked_at': datetime.now(timezone(timedelta(hours=8))).isoformat(), 'artist': args.artist, 'sources': {}}
    for name, action in [('beijing', lambda: beijing(probe, args.artist, args.pdf)),
                         ('guangzhou', lambda: guangzhou(probe, args.artist)),
                         ('damai', lambda: damai(probe, args.artist))]:
        try:
            result['sources'][name] = action()
        except Exception as error:
            result['sources'][name] = {'status': 'ERROR', 'error': type(error).__name__ + ': ' + str(error)}
    result['requests_sent'] = probe.count
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
