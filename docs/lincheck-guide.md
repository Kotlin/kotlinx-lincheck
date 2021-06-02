[//]: # (title: Lincheck guide)

## Lincheck guide
Writing concurrent algorithms is hard, however testing them is even more challenging.
That's why we have `Lincheck`, a practical tool for testing concurrent algorithms on JVM.

The main advantage of `Lincheck`  is that it provides a simple and declarative way to write concurrent tests.
Instead of describing how to perform the test, you specify what to test by declaring all the operations to examine along with the correctness property (linearizability is the default one).

This is a hands-on guide that will help you to get in touch with the tool and try the most practically useful features with examples.

## Table of contents

* [Create your first test with Lincheck](lincheck-test-tutorial.md)
* [Stress mode and model checking mode](testing-modes.md)
* [Execution constraints: single consumer/producer](constraints.md)
* [Parameter generation](parameter-generation.md)
* [Modular testing](modular-testing.md)
* [Progress guarantees](progress-guarantees.md)
* [Verification](verification.md)
* [Testing blocking data structures for Kotlin Coroutines](blocking-data-structures.md)

## Given talks

* [Lincheck. Testing concurrent data structures in Java](https://www.youtube.com/watch?v=YAb7YoEd6mM) (Heisenbug 2019, RU)
* [Testing concurrent algorithms with Lincheck](https://nkoval.com/talks/#lincheck-joker-2019) (Joker 2019, RU)
* [Lincheck: testing concurrent data structures on Java](https://nkoval.com/talks/#lincheck-hydra-2019) (Hydra 2019, RU)
* [Lock-free algorithms testing](https://nkoval.com/talks/#lock_free_algorithms_testing) (Joker 2017, RU)