package com.viagraphs.idb

import monifu.concurrent.atomic.Atomic
import monifu.reactive.Ack.{Cancel, Continue}
import monifu.reactive.{Ack, Observable, Observer}
import org.scalajs.dom._
import upickle.Aliases.{R, W}
import upickle._

import scala.concurrent.{Future, Promise}
import scala.language.higherKinds
import scala.scalajs.js
import scala.scalajs.js.UndefOr

abstract class Index[K : W : R : ValidKey, V : W : R] protected (initialName: String, dbRef: Atomic[Observable[IDBDatabase]]) extends IdbSupport[K,V](initialName, dbRef) {
  def indexName: String

  /**
   * Gets records by keys
   * @param keys keys of records to get - either [[scala.collection.Iterable]] or [[com.viagraphs.idb.IdbSupport.Key]]
   * @return observable of key-value pairs
   * @note that values might be undefined if a key doesn't exist !
   */
  def get[C[_]](keys: C[K])(implicit e: Tx[C]): Observable[(K,V)] = new IndexRequest[K, (K, V), C](keys, e) {
    val txAccess = ReadOnly(storeName)

    def executeOnIndex(store: IDBIndex, input: Either[K, Key[K]]) = input match {
      case Right(keyRange) =>
        store.openCursor(keyRange.range, keyRange.direction.value)
      case Left(key) =>
        store.get(json.writeJs(writeJs[K](key)).asInstanceOf[js.Any])
    }

    def execute(store: IDBObjectStore, input: Either[K, Key[K]]) = input match {
      case Right(keyRange) =>
        store.openCursor(keyRange.range, keyRange.direction.value)
      case Left(key) =>
        store.get(json.writeJs(writeJs[K](key)).asInstanceOf[js.Any])
    }

    def onSuccess(result: Either[(K, Any), IDBCursorWithValue], observer: Observer[(K, V)]): Future[Ack] = {
      result match {
        case Right(cursor) =>
          observer.onNext(
            readJs[K](json.readJs(cursor.key)) -> readJs[V](json.readJs(cursor.value))
          )
        case Left((key,value)) =>
          (value : UndefOr[Any]).fold[Future[Ack]](Continue) { anyVal =>
            observer.onNext(
              key -> readJs[V](json.readJs(anyVal))
            )
          }
      }
    }

    def onError(input: Option[K] = None) = s"getting ${input.getOrElse("")} from $storeName failed"

  }
}

class Store[K : W : R : ValidKey, V : W : R](initialName: String, dbRef: Atomic[Observable[IDBDatabase]]) extends Index[K,V](initialName, dbRef) {

  def index[IK : W : R : ValidKey](name: String) = new Index[IK, V](storeName, dbRef) {
    def indexName: String = name
  }

  /**
   * @param values to add to the object store either (K,V) pairs or values with autogenerated key or keypath
   * @return observable of added key-value pairs
   * @note that structured clones of values are created, beware that structure clone internal idb algorithm may fail
   */
  def add[I, C[X] <: Iterable[X]](values: C[I])(implicit p: StoreKeyPolicy[I], tx: Tx[C]): Observable[(K,V)] = {
    new Request[I, (K, V), C](values, tx) {
      val txAccess = ReadWrite(storeName)

      def execute(store: IDBObjectStore, input: Either[I, Key[I]]) = input match {
        case Left(entry) =>
          p.add(entry, store)
        case _ =>
          throw new IllegalStateException("Cannot happen, add doesn't support KeyRanges")
      }

      def onSuccess(result: Either[(I, Any), IDBCursorWithValue], observer: Observer[(K, V)]): Future[Ack] = {
        result match {
          case Left((in, key)) =>
            observer.onNext(readJs[K](json.readJs(key)) -> p.value(in))
          case _ =>
            throw new IllegalStateException("Cannot happen, add doesn't support KeyRanges")
        }
      }

      def onError(input: Option[I] = None) = s"adding ${input.getOrElse("")} to $storeName failed"
    }
  }

  /**
   * Updates store records matching keys with entries
   * @param keys keys of records to update - either [[scala.collection.Iterable]] or [[com.viagraphs.idb.IdbSupport.Key]]
   * @param entries map of key-value pairs to update store with
   * @return observable of the updated key-value pairs (the old values)
   * @note providing both keys and entries[key,value] is necessary due to IDB's cursor internal workings - I was unable to abstract updates better
   */
  def update[C[_]](keys: C[K], entries: Map[K,V])(implicit e: Tx[C]): Observable[(K,V)] = new Request[K, (K,V), C](keys, e) {
    def txAccess = ReadWrite(storeName)

    def execute(store: IDBObjectStore, input: Either[K, Key[K]]) = input match {
      case Right(keyRange) =>
        store.openCursor(keyRange.range, keyRange.direction.value)
      case Left(key) =>
        val value = entries.getOrElse(key, throw new IllegalArgumentException(s"Key $key is not present in update entries !"))
        store.put(
          json.writeJs(writeJs[V](value)).asInstanceOf[js.Any],
          json.writeJs(writeJs[K](key)).asInstanceOf[js.Any]
        )
    }

    def onSuccess(result: Either[(K, Any), IDBCursorWithValue], observer: Observer[(K, V)]): Future[Ack] = {
      result match {
        case Right(cursor) =>
          val promise = Promise[Ack]()
          val key = readJs[K](json.readJs(cursor.key))
          val oldVal = readJs[V](json.readJs(cursor.value))
          val newVal = entries.getOrElse(key, throw new IllegalArgumentException(s"Key $key is not present in update entries !"))
          val req = cursor.update(json.writeJs(writeJs[V](newVal)).asInstanceOf[js.Any])
          req.onsuccess = (e: Event) =>
            observer.onNext((key,oldVal))
            promise.success(Continue)
          req.onerror = (e: ErrorEvent) => {
            observer.onError(new IDbRequestException(s"Updating cursor '$key' with '$newVal' failed", req.error))
            promise.success(Cancel)
          }
          promise.future
        case Left((key,value)) =>
          (value : UndefOr[Any]).fold[Future[Ack]](Continue) { anyVal =>
            observer.onNext(
              key -> readJs[V](json.readJs(anyVal))
            )
          }
      }
    }
    def onError(input: Option[K]) = s"updating ${input.getOrElse("")} from $storeName failed"
  }

  /**
   * Delete records by keys
   * @param keys of records to delete
   * @return empty observable that either completes or errors out when records are deleted
   */
  def delete[C[_]](keys: C[K])(implicit e: Tx[C]): Observable[Nothing] = new Request[K, Nothing, C](keys, e) {
    val txAccess = ReadWrite(storeName)

    def execute(store: IDBObjectStore, input: Either[K, Key[K]]) = input match {
      case Right(keyRange) =>
        store.openCursor(keyRange.range, keyRange.direction.value)
      case Left(key) =>
        store.delete(json.writeJs(writeJs[K](key)).asInstanceOf[js.Any])
    }

    def onSuccess(result: Either[(K, Any), IDBCursorWithValue], observer: Observer[Nothing]): Future[Ack] = {
      result match {
        case Left(_) => Continue
        case Right(cursor) =>
          val key = cursor.key
          val promise = Promise[Ack]()
          val req = cursor.delete()
          req.onsuccess = (e: Event) =>
            promise.success(Continue)
          req.onerror = (e: ErrorEvent) => {
            observer.onError(new IDbRequestException(s"Deleting cursor '$key' failed", req.error))
            promise.success(Cancel)
          }
          promise.future
      }
    }

    def onError(input: Option[K] = None) = s"deleting ${input.getOrElse("")} from $storeName failed"
  }

  /**
   * @return observable of one element - count of records in this store
   */
  def count: Observable[Int] = {
    def errorMsg = s"Database.count($storeName) failed"
    Observable.create { subscriber =>
      val observer = subscriber.observer
      openTx(ReadOnly(storeName)).foreachWith(observer) { tx =>
        val req = tx.objectStore(storeName).count()
        req.onsuccess = (e: Event) => {
          observer.onNext(e.target.asInstanceOf[IDBOpenDBRequest].result.asInstanceOf[Int])
          observer.onComplete()
        }
        req.onerror = (e: ErrorEvent) => {
          observer.onError(new IDbRequestException(errorMsg, req.error))
        }
      }(storeTx => errorMsg)(IndexedDb.scheduler)
    }
  }

  /**
   * Deletes all records from this store
   */
  def clear: Observable[Nothing] = {
    def errorMsg = s"Database.clear($storeName) failed"
    Observable.create { subscriber =>
      val observer = subscriber.observer
      openTx(ReadWrite(storeName)).foreachWith(observer) { tx =>
        tx.objectStore(storeName).clear()
        tx.oncomplete = (e: Event) => {
          observer.onComplete()
        }
        tx.onerror = (e: ErrorEvent) => {
          observer.onError(new IDbRequestException(errorMsg, tx.error))
        }
      }(storeTx => errorMsg)(IndexedDb.scheduler)
    }
  }

  def indexName: String = ???
  import scala.scalajs.js.JSConverters._
  private[this] def openTx(txAccess: TxAccess): Observable[IDBTransaction] =
    Observable.create { subscriber =>
      val observer = subscriber.observer
      dbRef.get.foreachWith(observer) { db =>
        val tx = db.transaction(txAccess.storeNames.toJSArray, txAccess.value)
        observer.onNext(tx)
        observer.onComplete()
      }(db => s"Unable to openStoreTx $name in db ${db.name}")(IndexedDb.scheduler)
    }
}
