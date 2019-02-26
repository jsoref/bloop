package bloop.io

import java.io.IOException
import java.util.concurrent.ConcurrentLinkedDeque
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.{
  FileSystems,
  StandardCopyOption,
  FileVisitResult,
  FileVisitOption,
  FileVisitor,
  Files,
  Path,
  SimpleFileVisitor,
  Paths => NioPaths
}

import bloop.data.Project
import bloop.CompilerOracle
import bloop.engine.ExecutionContext

import monix.reactive.{MulticastStrategy, Consumer, Observable}
import monix.eval.Task

object SourceHasher {
  private final val sourceMatcher = FileSystems.getDefault.getPathMatcher("glob:**.{scala,java}")
  def findAndHashSourcesInProject(
      project: Project,
      parallelUnits: Int
  ): Task[List[CompilerOracle.HashedSource]] = {
    val sourceFilesAndDirectories = project.sources.distinct
    val (observer, observable) = Observable.multicast[Path](
      MulticastStrategy.publish
    )(ExecutionContext.ioScheduler)

    val discovery = new FileVisitor[Path] {
      def visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult = {
        val stop = {
          if (!sourceMatcher.matches(file)) false
          else {
            observer.onNext(file) == monix.execution.Ack.Stop
          }
        }

        if (stop) FileVisitResult.TERMINATE
        else FileVisitResult.CONTINUE
      }

      def visitFileFailed(
          t: Path,
          e: IOException
      ): FileVisitResult = FileVisitResult.CONTINUE

      def preVisitDirectory(
          directory: Path,
          attributes: BasicFileAttributes
      ): FileVisitResult = FileVisitResult.CONTINUE

      def postVisitDirectory(
          directory: Path,
          exception: IOException
      ): FileVisitResult = FileVisitResult.CONTINUE
    }

    val discoverFileTree = Task {
      val opts = java.util.EnumSet.of(FileVisitOption.FOLLOW_LINKS)
      sourceFilesAndDirectories.foreach { sourcePath =>
        Files.walkFileTree(sourcePath.underlying, opts, Int.MaxValue, discovery)
      }
    }.doOnFinish {
      case Some(t) => Task(observer.onError(t))
      case None => Task(observer.onComplete())
    }

    /*
    val hashedSources = new ConcurrentLinkedDeque[CompilerOracle.HashedSource]()
    val copyFilesInParallel = observable.consumeWith(Consumer.foreachParallelAsync(parallelUnits) {
      source =>
        Task {
          val hash = ByteHasher.hashFileContents(source.toFile)
          val hashed = CompilerOracle.HashedSource(AbsolutePath(source), hash)
          hashedSources.add(hashed)
          ()
        }
    })
     */

    val copyFilesInParallel = observable
      .mapAsync(parallelUnits) { source =>
        Task {
          val hash = ByteHasher.hashFileContents(source.toFile)
          CompilerOracle.HashedSource(AbsolutePath(source), hash)
        }
      }
      .toListL

    Task.mapBoth(discoverFileTree, copyFilesInParallel) {
      case (_, sources) =>
        sources
      /*
      import scala.collection.JavaConverters._
      hashedSources.iterator.asScala.foldLeft(Nil: List[CompilerOracle.HashedSource]) {
        case (acc, hashed) => hashed :: acc
      }
     */
    }
  }
}