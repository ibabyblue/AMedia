# Architecture

## Public interface

AMedia's stable boundary is `AMediaItem`, `AMediaQueueEntry`, `AMediaController`, immutable state,
progress, and durable events. Media3 experimental types are restricted to
`ExperimentalAMediaApi` interop and the Session module, whose Android service superclass is itself a
Media3 contract.

An item's URI is a location, not identity. `mediaId` identifies content; `entryId` identifies one
occurrence in a queue. This permits the same media to appear more than once without seek or event
updates crossing rows.

## Ownership

| Owner | Player count | Lifetime | Release |
| --- | ---: | --- | --- |
| `rememberAMediaController` | 1 | Compose call site | automatic on disposal |
| `AMediaControllerFactory` | 1 | caller scope | caller calls `release` |
| `AMediaPreloadSessionFactory` | 1 player + 1 preload manager | caller scope | caller calls `release` |
| `AMediaSessionService` | 1 | Android service | service `onDestroy` |
| `AMediaSessionConnection` | remote controller proxy | connection | caller calls `close` |

UI functions that receive a controller never release it.

## State and commands

Controller methods validate synchronously, enqueue accepted work on `Player.applicationLooper`, and
return. `StateFlow` is conflated current state. `SharedFlow<AMediaEvent>` carries durable edges such
as media change, first frame, seek completion, retry scheduling, terminal failure, and playback end.
Each media replacement increments `generation`, allowing consumers to reject stale asynchronous
work.

Progress polling runs only while `progress` has subscribers. The default interval is 250 ms.

## Preload invariant

An `AMediaPreloadSession` owns exactly one ExoPlayer and one `DefaultPreloadManager`, created by the
same Media3 builder. Ordered entries may be audio, video, or mixed media. `hint` changes preload
priority without starting playback; `play` obtains the preloaded source and activates it. Total
preload buffer allocation has a configurable budget; it is not a cap on all player/process memory.

After an `update` has executed on the player looper, the manager holds only the submitted entry set.
Eviction and replacement must remove by the stored MediaItem, not the original MediaSource: Media3
wraps sources before storing them. Reordering also replaces sources and must release the old
wrappers even when the MediaItem is equal. Unchanged entry/index pairs retain their wrappers.
The active player can still own an evicted source until it switches media or is released;
evicting a preload entry must not abruptly stop playback.

The module does not know pages, feeds, visible indexes, pagination, connectivity policy, or
application models. UI state holders map UI intent to entry IDs; ViewModels and repositories own
business data and policy.

## Request and cache chain

Each queue entry receives its own MediaSource and DataSource factory. Therefore its HTTP headers
apply to manifests and media segments, not only the first URI. Cache selection is also per item:
`None` bypasses cache, `ReadOnly` reads without writing, `ReadThrough` fills misses, and `CacheOnly`
has no upstream fallback. Explicit stable cache keys take precedence over URI identity.

Automatic eviction is size-bounded LRU. Manual maintenance can remove one resource, remove old
spans, trim immediately to a smaller byte target, or clear all data. Maintenance remains explicit;
AMedia does not infer product retention rules.

`AMediaCacheManager` adds logical cache spaces over one `SimpleCache`. Space-qualified storage keys
prevent equal application keys from colliding, while the shared `LeastRecentlyUsedCacheEvictor`
enforces one global byte budget across all spaces. A manager can take an aggregate snapshot and
apply one clear policy to all or selected spaces. Individual `AMediaCache` handles expose only their
own public keys, size, batch removal, and maintenance. Separate directory names remain the physical
store seam and therefore have independent budgets.

Manager and cache handles are reference counted. Closing a manager does not invalidate independently
opened cache handles, and active controllers or preload sessions retain their cache until release.

Logical spaces are a key namespace, not a filesystem layout. Media3 owns the physical files and its
index, and resources are stored as opaque spans rather than original media filenames. Applications
must treat `AMediaCache.directory` as an implementation detail and perform resource-, space-, or
store-wide maintenance through AMedia APIs. A distinct `directoryName` is the boundary for a truly
separate physical store, and external deletion is only safe after every owner of that store closes.

## Background policy

Background behavior is encoded into Media3 metadata so it survives the MediaSession binder boundary.
The service observes the application process lifecycle and applies the active item's policy. Compose
lifecycle binding uses the same policy and the controller's background capability.

## Non-goals for 0.1.x

- Offline download orchestration and licenses
- Cast or route selection
- Client/server ad insertion
- MediaLibrary browsing catalog
- Cross-process cache invalidation
