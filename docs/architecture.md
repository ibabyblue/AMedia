# Architecture

## Public boundary

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
| `rememberAMediaFeedController` | 1 | feed screen | automatic on disposal |
| `AMediaFeedControllerFactory` | 1 player + 1 preload manager | caller feed scope | caller calls `release` |
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

## Feed invariant

A feed screen owns exactly one ExoPlayer and one `DefaultPreloadManager`. Only the settled video page
is loaded into the player and only the active page composes `AMediaVideo`; placeholders remain host
UI. Positions are retained in an access-ordered bounded map and cleared after playback ends.

## Request and cache chain

Each queue entry receives its own MediaSource and DataSource factory. Therefore its HTTP headers
apply to manifests and media segments, not only the first URI. Cache selection is also per item:
`None` uses upstream directly and `ReadThrough` uses `CacheDataSource`. Explicit stable cache keys
take precedence over URI identity.

## Background policy

Background behavior is encoded into Media3 metadata so it survives the MediaSession binder boundary.
The service observes the application process lifecycle and applies the active item's policy. Compose
lifecycle binding uses the same policy and the controller's background capability.

## Non-goals for 0.1.0

- Offline download orchestration and licenses
- Cast or route selection
- Client/server ad insertion
- MediaLibrary browsing catalog
- Cross-process cache invalidation
