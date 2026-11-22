/*
 * ListTwo source adapter.
 *
 * Turns a `@listentwo/source-api` Source (our own source contract, built from
 * `listen-two-music-source/dist-runtime/<id>.mjs`) into an ArchiveTune module
 * provider: { id, name, version, author, description, searchTracks,
 * getTrackStreamUrl }.
 *
 * The packager (tools/lt/pack.mjs) appends this file to a source bundle and
 * substitutes the bundle's local name for its default export.
 */
;(function () {
  var bridge = function (action, payload) {
    if (typeof __moduleExtensionCall !== 'function') throw new Error('host bridge unavailable');
    var raw = __moduleExtensionCall(JSON.stringify(Object.assign({ action: action }, payload)));
    if (raw == null || raw === 'null') return null;
    return JSON.parse(raw);
  };

  // Our SDK expects HostResponse fields; module-host.js returns methods.
  // Bytes -> string, one char per byte (the SDK re-decodes GBK sources itself).
  var latin1 = function (bytes) {
    var text = '';
    for (var i = 0; i < bytes.length; i += 8192) {
      text += String.fromCharCode.apply(null, Array.prototype.slice.call(bytes, i, i + 8192));
    }
    return text;
  };

  // Our SDK expects HostResponse fields; module-host.js returns methods.
  // `text` stays real UTF-8 when the payload is valid UTF-8, and falls back to a
  // latin1 byte carry (kuwo/kugou/migu hand it to gbkDecode) otherwise.
  var host = {
    fetch: async function (input, init) {
      var response = await globalThis.fetch(String(input), init || {});
      var bytes = new Uint8Array(await response.arrayBuffer());
      var text;
      try { text = new TextDecoder('utf-8', { fatal: true }).decode(bytes); }
      catch (error) { text = latin1(bytes); }
      var json = null;
      try { json = JSON.parse(text); } catch (error) {}
      var headers = {};
      try { response.headers.forEach(function (value, key) { headers[key] = value; }); } catch (error) {}
      return { ok: !!response.ok, status: response.status, headers: headers, text: text, json: json };
    },
    getCookie: function (domain) {
      var value = bridge('cookieGet', { domain: String(domain) });
      return typeof value === 'string' && value.length > 0 ? value : null;
    },
    setCookie: function (domain, cookies) {
      bridge('cookieSet', { domain: String(domain), cookies: cookies == null ? '' : String(cookies) });
    },
  };

  var source = new __LT_DEFAULT__(host);
  var manifest = source.manifest();

  var toModuleTrack = function (track) {
    if (!track) return null;
    var artist = track.artist;
    var artists = Array.isArray(artist) ? artist : artist ? [artist] : [];
    var album = track.album;
    var seconds = typeof track.duration === 'number' && track.duration > 0 ? Math.round(track.duration) : null;
    return {
      id: track.id == null ? '' : String(track.id),
      title: String(track.title == null ? '' : track.title),
      artists: artists
        .map(function (item) { return { name: String((item && item.name) || '') }; })
        .filter(function (item) { return item.name.length > 0; }),
      album: album && album.title ? { title: String(album.title) } : null,
      durationMs: seconds == null ? null : seconds * 1000,
      explicit: typeof track.explicit === 'boolean' ? track.explicit : null,
      isrc: track.isrc ? String(track.isrc) : null,
      thumbnail: track.cover ? String(track.cover) : null,
    };
  };

  module.exports = {
    id: String(manifest.id),
    name: String(manifest.name),
    version: String(manifest.version),
    author: manifest.author ? String(manifest.author) : '',
    description: manifest.homepage ? String(manifest.homepage) : '',
    searchTracks: async function (query, limit, context, page) {
      var pageSize = Number(limit);
      if (!isFinite(pageSize) || pageSize <= 0) pageSize = 20;
      var pageNumber = Math.round(Number(page));
      if (!isFinite(pageNumber) || pageNumber < 1) pageNumber = 1;
      var result = await source.searchTracks(String(query == null ? '' : query), {
        page: pageNumber,
        pageSize: Math.min(100, Math.max(1, Math.round(pageSize))),
      });
      var content = result && Array.isArray(result.content) ? result.content : [];
      var tracks = [];
      for (var i = 0; i < content.length; i++) {
        var mapped = toModuleTrack(content[i]);
        if (mapped && mapped.id && mapped.title) tracks.push(mapped);
      }
      return tracks;
    },
    getTrackStreamUrl: async function (trackId, quality, context) {
      var params;
      if (quality) params = { quality: String(quality) };
      else if (context) params = { extra: context };
      var stream = await source.getTrackUrl(String(trackId), params);
      if (!stream || !stream.url) return null;
      return {
        streamUrl: String(stream.url),
        mimeType: stream.mimeType || undefined,
        headers: stream.headers || undefined,
        expiresAtMs: typeof stream.expiresAt === 'number' ? stream.expiresAt : undefined,
        sourceId: String(manifest.id),
      };
    },
  };
})();
