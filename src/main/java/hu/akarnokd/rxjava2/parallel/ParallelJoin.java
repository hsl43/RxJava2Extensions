/*
 * Copyright 2016 David Karnok
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package hu.akarnokd.rxjava2.parallel;

import java.util.concurrent.atomic.*;

import org.reactivestreams.*;

import io.reactivex.Flowable;
import io.reactivex.exceptions.Exceptions;
import io.reactivex.internal.fuseable.SimpleQueue;
import io.reactivex.internal.queue.SpscArrayQueue;
import io.reactivex.internal.subscriptions.SubscriptionHelper;
import io.reactivex.internal.util.*;
import io.reactivex.plugins.RxJavaPlugins;

/**
 * Merges the individual 'rails' of the source ParallelFlowable, unordered,
 * into a single regular Publisher sequence (exposed as Px).
 *
 * @param <T> the value type
 */
final class ParallelJoin<T> extends Flowable<T> {
    
    final ParallelFlowable<? extends T> source;
    
    final int prefetch;
    
    public ParallelJoin(ParallelFlowable<? extends T> source, int prefetch) {
        this.source = source;
        this.prefetch = prefetch;
    }
    
    @Override
    protected void subscribeActual(Subscriber<? super T> s) {
        JoinSubscription<T> parent = new JoinSubscription<T>(s, source.parallelism(), prefetch);
        s.onSubscribe(parent);
        source.subscribe(parent.subscribers);
    }
    
    static final class JoinSubscription<T> 
    extends AtomicInteger
    implements Subscription {
        /** */
        private static final long serialVersionUID = 3100232009247827843L;

        final Subscriber<? super T> actual;
        
        final JoinInnerSubscriber<T>[] subscribers;
        
        final AtomicThrowable error = new AtomicThrowable();

        final AtomicLong requested = new AtomicLong();
        
        volatile boolean cancelled;

        final AtomicInteger done = new AtomicInteger();

        public JoinSubscription(Subscriber<? super T> actual, int n, int prefetch) {
            this.actual = actual;
            @SuppressWarnings("unchecked")
            JoinInnerSubscriber<T>[] a = new JoinInnerSubscriber[n];
            
            for (int i = 0; i < n; i++) {
                a[i] = new JoinInnerSubscriber<T>(this, prefetch);
            }
            
            this.subscribers = a;
            done.lazySet(n);
        }
        
        @Override
        public void request(long n) {
            if (SubscriptionHelper.validate(n)) {
                BackpressureHelper.add(requested, n);
                drain();
            }
        }
        
        @Override
        public void cancel() {
            if (!cancelled) {
                cancelled = true;
                
                cancelAll();
                
                if (getAndIncrement() == 0) {
                    cleanup();
                }
            }
        }
        
        void cancelAll() {
            for (JoinInnerSubscriber<T> s : subscribers) {
                s.cancel();
            }
        }
        
        void cleanup() {
            for (JoinInnerSubscriber<T> s : subscribers) {
                s.queue = null; 
            }
        }
        
        void onNext(JoinInnerSubscriber<T> inner, T value) {
            if (get() == 0 && compareAndSet(0, 1)) {
                if (requested.get() != 0) {
                    actual.onNext(value);
                    if (requested.get() != Long.MAX_VALUE) {
                        requested.decrementAndGet();
                    }
                    inner.request(1);
                } else {
                    SimpleQueue<T> q = inner.getQueue();

                    // FIXME overflow handling
                    q.offer(value);
                }
                if (decrementAndGet() == 0) {
                    return;
                }
            } else {
                SimpleQueue<T> q = inner.getQueue();
                
                // FIXME overflow handling
                q.offer(value);

                if (getAndIncrement() != 0) {
                    return;
                }
            }
            
            drainLoop();
        }
        
        void onError(Throwable e) {
            if (error.addThrowable(e)) {
                cancelAll();
                drain();
            } else {
                RxJavaPlugins.onError(e);
            }
        }
        
        void onComplete() {
            done.decrementAndGet();
            drain();
        }
        
        void drain() {
            if (getAndIncrement() != 0) {
                return;
            }
            
            drainLoop();
        }
        
        void drainLoop() {
            int missed = 1;
            
            JoinInnerSubscriber<T>[] s = this.subscribers;
            int n = s.length;
            Subscriber<? super T> a = this.actual;
            
            for (;;) {
                
                long r = requested.get();
                long e = 0;
                
                middle:
                while (e != r) {
                    if (cancelled) {
                        cleanup();
                        return;
                    }
                    
                    Throwable ex = error.get();
                    if (ex != null) {
                        cleanup();
                        a.onError(error.terminate());
                        return;
                    }
                    
                    boolean d = done.get() == 0;
                    
                    boolean empty = true;
                    
                    for (int i = 0; i < n; i++) {
                        JoinInnerSubscriber<T> inner = s[i];
                        
                        SimpleQueue<T> q = inner.queue;
                        if (q != null) {
                            T v;
                            
                            try {
                                v = q.poll();
                            } catch (Throwable exc) {
                                Exceptions.throwIfFatal(exc);
                                error.addThrowable(exc);
                                cancelAll();
                                
                                actual.onError(error.terminate());
                                return;
                            }
                            
                            if (v != null) {
                                empty = false;
                                a.onNext(v);
                                inner.requestOne();
                                if (++e == r) {
                                    break middle;
                                }
                            }
                        }
                    }
                    
                    if (d && empty) {
                        a.onComplete();
                        return;
                    }
                    
                    if (empty) {
                        break;
                    }
                }
                
                if (e == r) {
                    if (cancelled) {
                        cleanup();
                        return;
                    }
                    
                    Throwable ex = error.get();
                    if (ex != null) {
                        cleanup();
                        a.onError(error.terminate());
                        return;
                    }
                    
                    boolean d = done.get() == 0;
                    
                    boolean empty = true;
                    
                    for (int i = 0; i < n; i++) {
                        JoinInnerSubscriber<T> inner = s[i];
                        
                        SimpleQueue<T> q = inner.queue;
                        if (q != null && !q.isEmpty()) {
                            empty = false;
                            break;
                        }
                    }
                    
                    if (d && empty) {
                        a.onComplete();
                        return;
                    }
                }
                
                if (e != 0 && r != Long.MAX_VALUE) {
                    requested.addAndGet(-e);
                }
                
                int w = get();
                if (w == missed) {
                    missed = addAndGet(-missed);
                    if (missed == 0) {
                        break;
                    }
                } else {
                    missed = w;
                }
            }
        }
    }
    
    static final class JoinInnerSubscriber<T> 
    extends AtomicReference<Subscription>
    implements Subscriber<T> {
        
        /** */
        private static final long serialVersionUID = 8410034718427740355L;

        final JoinSubscription<T> parent;
        
        final int prefetch;
        
        final int limit;
        
        long produced;
        
        volatile SimpleQueue<T> queue;
        
        volatile boolean done;
        
        public JoinInnerSubscriber(JoinSubscription<T> parent, int prefetch) {
            this.parent = parent;
            this.prefetch = prefetch ;
            this.limit = prefetch - (prefetch >> 2);
        }
        
        @Override
        public void onSubscribe(Subscription s) {
            if (SubscriptionHelper.setOnce(this, s)) {
                s.request(prefetch);
            }
        }
        
        @Override
        public void onNext(T t) {
            parent.onNext(this, t);
        }
        
        @Override
        public void onError(Throwable t) {
            parent.onError(t);
        }
        
        @Override
        public void onComplete() {
            parent.onComplete();
        }
        
        public void requestOne() {
            long p = produced + 1;
            if (p == limit) {
                produced = 0;
                get().request(p);
            } else {
                produced = p;
            }
        }

        public void request(long n) {
            long p = produced + n;
            if (p >= limit) {
                produced = 0;
                get().request(p);
            } else {
                produced = p;
            }
        }

        public void cancel() {
            SubscriptionHelper.cancel(this);
        }
        
        SimpleQueue<T> getQueue() {
            SimpleQueue<T> q = queue;
            if (q == null) {
                q = new SpscArrayQueue<T>(prefetch);
                this.queue = q;
            }
            return q;
        }
    }
}
