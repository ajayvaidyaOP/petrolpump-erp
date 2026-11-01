import { useRef } from 'react';
import { idempotencyKey } from './api';

/** Stable across retries of the same logical operation; a new payload gets a new key. */
export function createIdempotencyTracker(prefix: string, generate: () => string = () => idempotencyKey(prefix)) {
  let fingerprint: string | undefined;
  let key: string | undefined;
  return {
    forPayload(payload: unknown): string {
      const next = JSON.stringify(payload) ?? 'null';
      if (next !== fingerprint || key === undefined) {
        fingerprint = next;
        key = generate();
      }
      return key;
    },
    reset(): void {
      fingerprint = undefined;
      key = undefined;
    },
  };
}

/** Reset only after the server confirms success, never before a network retry. */
export function useIdempotencyKey(prefix: string, payload: unknown) {
  const tracker = useRef<ReturnType<typeof createIdempotencyTracker> | null>(null);
  if (!tracker.current) tracker.current = createIdempotencyTracker(prefix);
  return {
    key: tracker.current.forPayload(payload),
    reset: () => tracker.current?.reset(),
  };
}

/** For mutations whose payload is only known inside mutationFn. */
export function useIdempotencyTracker(prefix: string) {
  const tracker = useRef<ReturnType<typeof createIdempotencyTracker> | null>(null);
  if (!tracker.current) tracker.current = createIdempotencyTracker(prefix);
  return {
    keyFor: (payload: unknown) => tracker.current!.forPayload(payload),
    reset: () => tracker.current?.reset(),
  };
}
