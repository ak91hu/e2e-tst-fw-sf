# Bundled quality assets

The web engine includes unmodified `axe.min.js` from **axe-core 4.14.0**, published by Deque Systems on the npm registry. Its [MPL license](../sf-e2e-engine-playwright/src/main/resources/hu/ak91/sf/e2e/engine/AXE-LICENSE.txt) accompanies the asset. Upstream project: [axe-core](https://github.com/dequelabs/axe-core).

Pinned npm tarball: `https://registry.npmjs.org/axe-core/-/axe-core-4.14.0.tgz`.

Verified tarball SHA-512 (base64):

```text
9WTZxEjsZ7b13TH8JPmbV2z8CHbl80/2hm3XPEG4JgNdQLK81IBRXmSxHfMAOkSqQeRxT/0dwNDz2GOm3zzpcQ==
```

Bundled script SHA-256:

```text
20c09fe157a8a34a30e241aaa1fcdade657734f08ab379ecfbeb7d45cc46e878
```

The asset runs locally in approved browser frames; it does not require a runtime CDN request. Rule/impact/target-hash projection is implemented by the framework rather than modifying the third-party source. Changes to this asset/version require reviewed provenance, license retention and rerunning the real accessibility defect harness.

The separately bundled official Allure distribution and its version/hash-specific cache are described in [Allure reporting](ALLURE_REPORTING.md). Maven dependencies retain their upstream licenses; browser binaries are installed separately through the pinned Playwright CLI.
