import pytest

from pipeline import collect as c


def test_human_short_exception_only_changes_duration():
    args = c.parser().parse_args([])
    info = dict(id='00000000001',duration=427,channel_id='channel',title='한국어 합성',language='ko')
    def decisions(name,decision='approve'):
        return {info['id']:dict(decision=decision,decided_by=name)}
    assert c.filter_reasons(info,args,decisions('user')) == []
    for name in ('','claude'):
        assert c.filter_reasons(info,args,decisions(name)) == ['duration_too_short']
    assert c.filter_reasons(info,args,decisions('user','hold')) == ['duration_too_short']
    assert c.filter_reasons(info | dict(duration=179),args,decisions('user')) == ['duration_too_short']
    assert c.filter_reasons(info | dict(duration=180),args,decisions('user')) == []
    assert 'live_or_upcoming' in c.filter_reasons(info | dict(is_live=True),args,decisions('user'))
    assert c.filter_reasons(info | dict(duration=10801),args,decisions('user')) == ['duration_too_long']


def test_retry_narrow_offline_and_other_cells_unchanged(tmp_path):
    manifest = c.Manifest(tmp_path/'data/sources.tsv',tmp_path/'scratch')
    manifest.update('00000000001',duration_s=427,status='rejected',note='duration_too_short',title='합성',channel_id='one')
    manifest.update('00000000002',duration_s=427,status='rejected',note='duration_too_short;music_or_performance',channel_id='two')
    before = {k:dict(v) for k,v in manifest.rows.items()}
    decisions = {k:dict(decision='approve',decided_by='user') for k in manifest.rows}
    with pytest.raises(ValueError):
        c.retry_short_approved(manifest,decisions,list(manifest.rows))
    assert manifest.rows == before
    c.retry_short_approved(manifest,decisions,['00000000001'])
    assert manifest.rows['00000000001'] == before['00000000001'] | dict(status='candidate',note='')
    assert manifest.rows['00000000002'] == before['00000000002']


def test_short_human_download_and_prescreen_reject(tmp_path):
    manifest = c.Manifest(tmp_path/'data/sources.tsv',tmp_path/'scratch')
    for n in (1,2):
        manifest.update(f'{n:011d}',duration_s=427,status='candidate',channel_id='one')
    class Backend:
        audio_dir = tmp_path/'audio'
        downloads = []
        def metadata(self,video_id):
            return dict(id=video_id,duration=427,channel_id='one',title='합성 한국어',language='ko')
        def download(self,video_id):
            self.downloads.append(video_id)
            self.audio_dir.mkdir(exist_ok=True)
            (self.audio_dir/f'{video_id}.webm').write_bytes(b'synthetic')
    backend = Backend()
    decisions = {'00000000001':dict(decision='approve',decided_by='user'),
                 '00000000002':dict(decision='approve',decided_by='claude')}
    result = c.collect(manifest,backend,c.parser().parse_args([]),decisions)
    assert result['downloaded'] == 1 and backend.downloads == ['00000000001']
    assert manifest.rows['00000000001']['status'] == 'downloaded'
    assert manifest.rows['00000000002']['status'] == 'rejected'
    assert manifest.rows['00000000002']['note'] == 'duration_too_short'
