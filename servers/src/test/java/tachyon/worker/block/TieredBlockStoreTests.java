/*
 * Licensed to the University of California, Berkeley under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for additional information regarding
 * copyright ownership. The ASF licenses this file to You under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License. You may obtain a
 * copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package tachyon.worker.block;

import java.io.File;
import java.lang.reflect.Field;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.rules.TemporaryFolder;

import com.google.common.collect.Sets;

import tachyon.StorageLevelAlias;
import tachyon.conf.TachyonConf;
import tachyon.exception.AlreadyExistsException;
import tachyon.exception.InvalidStateException;
import tachyon.exception.NotFoundException;
import tachyon.exception.OutOfSpaceException;
import tachyon.util.io.FileUtils;
import tachyon.worker.WorkerContext;
import tachyon.worker.block.evictor.Evictor;
import tachyon.worker.block.meta.BlockMeta;
import tachyon.worker.block.meta.StorageDir;
import tachyon.worker.block.meta.TempBlockMeta;

public final class TieredBlockStoreTests {
  private static final long USER_ID1 = 2;
  private static final long USER_ID2 = 3;
  private static final long BLOCK_ID1 = 1000;
  private static final long BLOCK_ID2 = 1001;
  private static final long TEMP_BLOCK_ID = 1003;
  private static final long BLOCK_SIZE = 512;
  private static final StorageLevelAlias FIRST_TIER_ALIAS = TieredBlockStoreTestUtils.TIER_ALIAS[0];
  private TieredBlockStore mBlockStore;
  private BlockMetadataManager mMetaManager;
  private BlockLockManager mLockManager;
  private StorageDir mTestDir1;
  private StorageDir mTestDir2;
  private Evictor mEvictor;

  @Rule
  public TemporaryFolder mTestFolder = new TemporaryFolder();

  @Rule
  public ExpectedException mThrown = ExpectedException.none();

  @Before
  public void before() throws Exception {
    File tempFolder = mTestFolder.newFolder();
    TachyonConf testConf =
        TieredBlockStoreTestUtils.defaultTachyonConf(tempFolder.getAbsolutePath());
    WorkerContext.getConf().merge(testConf);
    mBlockStore = new TieredBlockStore();

    // TODO: avoid using reflection to get private members.
    Field field = mBlockStore.getClass().getDeclaredField("mMetaManager");
    field.setAccessible(true);
    mMetaManager = (BlockMetadataManager) field.get(mBlockStore);
    field = mBlockStore.getClass().getDeclaredField("mLockManager");
    field.setAccessible(true);
    mLockManager = (BlockLockManager) field.get(mBlockStore);
    field = mBlockStore.getClass().getDeclaredField("mEvictor");
    field.setAccessible(true);
    mEvictor = (Evictor) field.get(mBlockStore);

    mTestDir1 = mMetaManager.getTier(FIRST_TIER_ALIAS.getValue()).getDir(0);
    mTestDir2 = mMetaManager.getTier(FIRST_TIER_ALIAS.getValue()).getDir(1);
  }

  // Different users can concurrently grab block locks on different blocks
  @Test
  public void differentUserLockDifferentBlocksTest() throws Exception {
    TieredBlockStoreTestUtils.cache(USER_ID1, BLOCK_ID1, BLOCK_SIZE, mTestDir1, mMetaManager,
        mEvictor);
    TieredBlockStoreTestUtils.cache(USER_ID2, BLOCK_ID2, BLOCK_SIZE, mTestDir2, mMetaManager,
        mEvictor);

    long lockId1 = mBlockStore.lockBlock(USER_ID1, BLOCK_ID1);
    Assert.assertTrue(
        Sets.difference(mLockManager.getLockedBlocks(), Sets.newHashSet(BLOCK_ID1)).isEmpty());

    long lockId2 = mBlockStore.lockBlock(USER_ID2, BLOCK_ID2);
    Assert.assertNotEquals(lockId1, lockId2);
    Assert.assertTrue(
        Sets.difference(mLockManager.getLockedBlocks(), Sets.newHashSet(BLOCK_ID1, BLOCK_ID2))
            .isEmpty());

    mBlockStore.unlockBlock(lockId2);
    Assert.assertTrue(
        Sets.difference(mLockManager.getLockedBlocks(), Sets.newHashSet(BLOCK_ID1)).isEmpty());

    mBlockStore.unlockBlock(lockId1);
    Assert.assertTrue(mLockManager.getLockedBlocks().isEmpty());
  }

  // Same user can concurrently grab block locks on different block
  @Test
  public void sameUserLockDifferentBlocksTest() throws Exception {
    TieredBlockStoreTestUtils.cache(USER_ID1, BLOCK_ID1, BLOCK_SIZE, mTestDir1, mMetaManager,
        mEvictor);
    TieredBlockStoreTestUtils.cache(USER_ID1, BLOCK_ID2, BLOCK_SIZE, mTestDir2, mMetaManager,
        mEvictor);

    long lockId1 = mBlockStore.lockBlock(USER_ID1, BLOCK_ID1);
    Assert.assertTrue(
        Sets.difference(mLockManager.getLockedBlocks(), Sets.newHashSet(BLOCK_ID1)).isEmpty());

    long lockId2 = mBlockStore.lockBlock(USER_ID1, BLOCK_ID2);
    Assert.assertNotEquals(lockId1, lockId2);
  }

  @Test
  public void lockNonExistingBlockTest() throws Exception {
    mThrown.expect(NotFoundException.class);
    mThrown.expectMessage("Failed to lockBlock: no blockId " + BLOCK_ID1 + " found");

    mBlockStore.lockBlock(USER_ID1, BLOCK_ID1);
  }

  @Test
  public void unlockNonExistingLockTest() throws Exception {
    long badLockId = 1003;
    mThrown.expect(NotFoundException.class);
    mThrown.expectMessage("Failed to unlockBlock: lockId " + badLockId + " has no lock record");

    mBlockStore.unlockBlock(badLockId);
  }

  @Test
  public void commitBlockTest() throws Exception {
    TieredBlockStoreTestUtils.createTempBlock(USER_ID1, TEMP_BLOCK_ID, BLOCK_SIZE, mTestDir1);
    Assert.assertFalse(mBlockStore.hasBlockMeta(TEMP_BLOCK_ID));
    mBlockStore.commitBlock(USER_ID1, TEMP_BLOCK_ID);
    Assert.assertTrue(mBlockStore.hasBlockMeta(TEMP_BLOCK_ID));
    Assert
        .assertFalse(FileUtils.exists(TempBlockMeta.tempPath(mTestDir1, USER_ID1, TEMP_BLOCK_ID)));
    Assert.assertTrue(FileUtils.exists(TempBlockMeta.commitPath(mTestDir1, TEMP_BLOCK_ID)));
  }

  @Test
  public void abortBlockTest() throws Exception {
    TieredBlockStoreTestUtils.createTempBlock(USER_ID1, TEMP_BLOCK_ID, BLOCK_SIZE, mTestDir1);
    mBlockStore.abortBlock(USER_ID1, TEMP_BLOCK_ID);
    Assert.assertFalse(mTestDir1.hasBlockMeta(BLOCK_ID1));
    Assert.assertFalse(mBlockStore.hasBlockMeta(TEMP_BLOCK_ID));
    Assert
        .assertFalse(FileUtils.exists(TempBlockMeta.tempPath(mTestDir1, USER_ID1, TEMP_BLOCK_ID)));
    Assert.assertFalse(FileUtils.exists(TempBlockMeta.commitPath(mTestDir1, TEMP_BLOCK_ID)));
  }

  @Test
  public void moveBlockTest() throws Exception {
    TieredBlockStoreTestUtils.cache(USER_ID1, BLOCK_ID1, BLOCK_SIZE, mTestDir1, mMetaManager,
        mEvictor);
    mBlockStore.moveBlock(USER_ID1, BLOCK_ID1, mTestDir2.toBlockStoreLocation());
    Assert.assertFalse(mTestDir1.hasBlockMeta(BLOCK_ID1));
    Assert.assertTrue(mTestDir2.hasBlockMeta(BLOCK_ID1));
    Assert.assertTrue(mBlockStore.hasBlockMeta(BLOCK_ID1));
    Assert.assertFalse(FileUtils.exists(BlockMeta.commitPath(mTestDir1, BLOCK_ID1)));
    Assert.assertTrue(FileUtils.exists(BlockMeta.commitPath(mTestDir2, BLOCK_ID1)));
  }

  @Test
  public void removeBlockTest() throws Exception {
    TieredBlockStoreTestUtils.cache(USER_ID1, BLOCK_ID1, BLOCK_SIZE, mTestDir1, mMetaManager,
        mEvictor);
    mBlockStore.removeBlock(USER_ID1, BLOCK_ID1);
    Assert.assertFalse(mTestDir1.hasBlockMeta(BLOCK_ID1));
    Assert.assertFalse(mBlockStore.hasBlockMeta(BLOCK_ID1));
    Assert.assertFalse(FileUtils.exists(BlockMeta.commitPath(mTestDir1, BLOCK_ID1)));
  }

  @Test
  public void freeSpaceTest() throws Exception {
    TieredBlockStoreTestUtils.cache(USER_ID1, BLOCK_ID1, BLOCK_SIZE, mTestDir1, mMetaManager,
        mEvictor);
    mBlockStore.freeSpace(USER_ID1, mTestDir1.getCapacityBytes(), mTestDir1.toBlockStoreLocation());
    // Expect BLOCK_ID1 to be moved out of mTestDir1
    Assert.assertEquals(mTestDir1.getCapacityBytes(), mTestDir1.getAvailableBytes());
    Assert.assertFalse(mTestDir1.hasBlockMeta(BLOCK_ID1));
    Assert.assertFalse(FileUtils.exists(BlockMeta.commitPath(mTestDir1, BLOCK_ID1)));
  }

  @Test
  public void requestSpaceTest() throws Exception {
    TieredBlockStoreTestUtils.createTempBlock(USER_ID1, TEMP_BLOCK_ID, 1, mTestDir1);
    mBlockStore.requestSpace(USER_ID1, TEMP_BLOCK_ID, BLOCK_SIZE - 1);
    Assert.assertTrue(mTestDir1.hasTempBlockMeta(TEMP_BLOCK_ID));
    Assert.assertEquals(BLOCK_SIZE, mTestDir1.getTempBlockMeta(TEMP_BLOCK_ID).getBlockSize());
    Assert.assertEquals(mTestDir1.getCapacityBytes() - BLOCK_SIZE, mTestDir1.getAvailableBytes());
  }

  @Test
  public void createBlockMetaWithoutEvictionTest() throws Exception {
    TempBlockMeta tempBlockMeta =
        mBlockStore.createBlockMeta(USER_ID1, TEMP_BLOCK_ID, mTestDir1.toBlockStoreLocation(), 1);
    Assert.assertEquals(1, tempBlockMeta.getBlockSize());
    Assert.assertEquals(mTestDir1, tempBlockMeta.getParentDir());
  }

  @Test
  public void createBlockMetaWithEvictionTest() throws Exception {
    TieredBlockStoreTestUtils.cache(USER_ID1, BLOCK_ID1, BLOCK_SIZE, mTestDir1, mMetaManager,
        mEvictor);
    TempBlockMeta tempBlockMeta = mBlockStore.createBlockMeta(USER_ID1, TEMP_BLOCK_ID,
        mTestDir1.toBlockStoreLocation(), mTestDir1.getCapacityBytes());
    // Expect BLOCK_ID1 evicted from mTestDir1
    Assert.assertFalse(mTestDir1.hasBlockMeta(BLOCK_ID1));
    Assert.assertFalse(FileUtils.exists(BlockMeta.commitPath(mTestDir1, BLOCK_ID1)));
    Assert.assertEquals(mTestDir1.getCapacityBytes(), tempBlockMeta.getBlockSize());
    Assert.assertEquals(mTestDir1, tempBlockMeta.getParentDir());
  }

  // When creating a block, if the space of the target location is currently taken by another block
  // being locked, this creation operation will fail until the lock released.
  @Test
  public void createBlockMetaWithBlockLockedTest() throws Exception {
    TieredBlockStoreTestUtils.cache(USER_ID1, BLOCK_ID1, BLOCK_SIZE, mTestDir1, mMetaManager,
        mEvictor);

    // User1 locks a block first
    long lockId = mBlockStore.lockBlock(USER_ID1, BLOCK_ID1);

    // Expect an exception because no eviction plan is feasible
    mThrown.expect(OutOfSpaceException.class);
    mThrown.expectMessage("Failed to free space: no eviction plan by evictor");
    mBlockStore.createBlockMeta(USER_ID1, TEMP_BLOCK_ID, mTestDir1.toBlockStoreLocation(),
        mTestDir1.getCapacityBytes());

    // Expect createBlockMeta to succeed after unlocking this block.
    mBlockStore.unlockBlock(lockId);
    mBlockStore.createBlockMeta(USER_ID1, TEMP_BLOCK_ID, mTestDir1.toBlockStoreLocation(),
        mTestDir1.getCapacityBytes());
    Assert.assertEquals(0, mTestDir1.getAvailableBytes());
  }

  // When moving a block from src location to dst, if the space of the dst location is currently
  // taken by another block being locked, this move operation will fail until the lock released.
  @Test
  public void moveBlockMetaWithBlockLockedTest() throws Exception {
    // Setup the src dir containing the block to move
    TieredBlockStoreTestUtils.cache(USER_ID1, BLOCK_ID1, BLOCK_SIZE, mTestDir1, mMetaManager,
        mEvictor);
    // Setup the dst dir whose space is totally taken by another block
    TieredBlockStoreTestUtils.cache(USER_ID1, BLOCK_ID2, mTestDir2.getCapacityBytes(), mTestDir2,
        mMetaManager, mEvictor);

    // User1 locks block2 first
    long lockId = mBlockStore.lockBlock(USER_ID1, BLOCK_ID2);

    // Expect an exception because no eviction plan is feasible
    mThrown.expect(OutOfSpaceException.class);
    mThrown.expectMessage("Failed to free space: no eviction plan by evictor");
    mBlockStore.moveBlock(USER_ID1, BLOCK_ID1, mTestDir2.toBlockStoreLocation());

    // Expect createBlockMeta to succeed after unlocking this block.
    mBlockStore.unlockBlock(lockId);
    mBlockStore.moveBlock(USER_ID1, BLOCK_ID1, mTestDir2.toBlockStoreLocation());

    Assert.assertEquals(mTestDir1.getCapacityBytes(), mTestDir1.getAvailableBytes());
    Assert.assertEquals(mTestDir2.getCapacityBytes() - BLOCK_SIZE, mTestDir2.getAvailableBytes());
  }

  // When free the space of a location, if the space of the target location is currently taken by
  // another block being locked, this freeSpace operation will fail until the lock released.
  @Test
  public void freeSpaceWithBlockLockedTest() throws Exception {
    TieredBlockStoreTestUtils.cache(USER_ID1, BLOCK_ID1, BLOCK_SIZE, mTestDir1, mMetaManager,
        mEvictor);

    // User1 locks a block first
    long lockId = mBlockStore.lockBlock(USER_ID1, BLOCK_ID1);

    // Expect an exception as no eviction plan is feasible
    mThrown.expect(OutOfSpaceException.class);
    mThrown.expectMessage("Failed to free space: no eviction plan by evictor");
    mBlockStore.freeSpace(USER_ID1, mTestDir1.getCapacityBytes(), mTestDir1.toBlockStoreLocation());

    // Expect freeSpace to succeed after unlock this block.
    mBlockStore.unlockBlock(lockId);
    mBlockStore.freeSpace(USER_ID1, mTestDir1.getCapacityBytes(), mTestDir1.toBlockStoreLocation());
    Assert.assertEquals(mTestDir1.getCapacityBytes(), mTestDir1.getAvailableBytes());
  }

  @Test
  public void getBlockWriterForNonExistingBlockTest() throws Exception {
    mThrown.expect(NotFoundException.class);
    mThrown.expectMessage("Failed to get TempBlockMeta: temp blockId " + BLOCK_ID1 + " not found");

    mBlockStore.getBlockWriter(USER_ID1, BLOCK_ID1);
  }

  @Test
  public void abortNonExistingBlockTest() throws Exception {
    mThrown.expect(NotFoundException.class);
    mThrown.expectMessage("Failed to get TempBlockMeta: temp blockId " + BLOCK_ID1 + " not found");

    mBlockStore.abortBlock(USER_ID1, BLOCK_ID1);
  }

  @Test
  public void abortBlockNotOwnedByUserIdTest() throws Exception {
    mThrown.expect(InvalidStateException.class);
    mThrown.expectMessage("checkTempBlockOwnedByUser failed: ownerUserId of blockId "
        + TEMP_BLOCK_ID + " is " + USER_ID1 + " but userId passed in is " + USER_ID2);

    TieredBlockStoreTestUtils.createTempBlock(USER_ID1, TEMP_BLOCK_ID, BLOCK_SIZE, mTestDir1);
    mBlockStore.abortBlock(USER_ID2, TEMP_BLOCK_ID);
  }

  @Test
  public void abortCommitedBlockTest() throws Exception {
    mThrown.expect(AlreadyExistsException.class);
    mThrown.expectMessage(
        "checkTempBlockOwnedByUser failed: blockId " + TEMP_BLOCK_ID + " is committed");

    TieredBlockStoreTestUtils.createTempBlock(USER_ID1, TEMP_BLOCK_ID, BLOCK_SIZE, mTestDir1);
    mBlockStore.commitBlock(USER_ID1, TEMP_BLOCK_ID);
    mBlockStore.abortBlock(USER_ID1, TEMP_BLOCK_ID);
  }

  @Test
  public void commitBlockTwiceTest() throws Exception {
    mThrown.expect(AlreadyExistsException.class);
    mThrown.expectMessage(
        "checkTempBlockOwnedByUser failed: blockId " + TEMP_BLOCK_ID + " is committed");

    TieredBlockStoreTestUtils.createTempBlock(USER_ID1, TEMP_BLOCK_ID, BLOCK_SIZE, mTestDir1);
    mBlockStore.commitBlock(USER_ID1, TEMP_BLOCK_ID);
    mBlockStore.commitBlock(USER_ID1, TEMP_BLOCK_ID);
  }

  @Test
  public void commitNonExistingBlockTest() throws Exception {
    mThrown.expect(NotFoundException.class);
    mThrown.expectMessage("Failed to get TempBlockMeta: temp blockId " + BLOCK_ID1 + " not found");

    mBlockStore.commitBlock(USER_ID1, BLOCK_ID1);
  }

  @Test
  public void commitBlockNotOwnedByUserIdTest() throws Exception {
    mThrown.expect(InvalidStateException.class);
    mThrown.expectMessage("checkTempBlockOwnedByUser failed: ownerUserId of blockId "
        + TEMP_BLOCK_ID + " is " + USER_ID1 + " but userId passed in is " + USER_ID2);

    TieredBlockStoreTestUtils.createTempBlock(USER_ID1, TEMP_BLOCK_ID, BLOCK_SIZE, mTestDir1);
    mBlockStore.commitBlock(USER_ID2, TEMP_BLOCK_ID);
  }

  @Test
  public void removeTempBlockTest() throws Exception {
    mThrown.expect(InvalidStateException.class);
    mThrown.expectMessage("Failed to remove block " + TEMP_BLOCK_ID + ": block is uncommitted");

    TieredBlockStoreTestUtils.createTempBlock(USER_ID1, TEMP_BLOCK_ID, BLOCK_SIZE, mTestDir1);
    mBlockStore.removeBlock(USER_ID1, TEMP_BLOCK_ID);
  }

  @Test
  public void removeNonExistingBlockTest() throws Exception {
    mThrown.expect(NotFoundException.class);
    mThrown.expectMessage("Failed to get BlockMeta: blockId " + BLOCK_ID1 + " not found");

    mBlockStore.removeBlock(USER_ID1, BLOCK_ID1);
  }
}
