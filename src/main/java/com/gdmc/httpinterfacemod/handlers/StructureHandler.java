package com.gdmc.httpinterfacemod.handlers;

import com.google.gson.JsonObject;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

public class StructureHandler extends HandlerBase {

	// POST/GET: x, y, z positions
	private int x;
	private int y;
	private int z;

	// GET: Ranges in the x, y, z directions (can be negative). Defaults to 1.
	private int dx;
	private int dy;
	private int dz;

	// POST: If set, mirror the input structure on the x or z axis. Valid values are "x", "z" and unset.
	private String mirror;

	// POST: If set, rotate the input structure 0, 1, 2, 3 times in 90° clock-wise.
	private int rotate;

	// POST: set pivot point for the rotation. Values are relative to origin of the structure.
	private int pivotX;
	private int pivotZ;

	// POST/GET: Whether to include entities (mobs, villagers, items) in placing/getting a structure.
	private boolean includeEntities;

	// POST: Defaults to true. If true, update neighbouring blocks after placement.
	private boolean doBlockUpdates;

	// POST: Defaults to false. If true, block updates cause item drops after placement.
	private boolean spawnDrops;

	// POST: Overrides both doBlockUpdates and spawnDrops if set. For more information see #getBlockFlags and
	// http://minecraft.wiki/w/Block_update
	private int customFlags; // -1 == no custom flags
	private String dimension;

	public StructureHandler(MinecraftServer mcServer) {
		super(mcServer);
	}

	@Override
	protected void internalHandle(HttpExchange httpExchange) throws IOException {

		// query parameters
		Map<String, String> queryParams = parseQueryString(httpExchange.getRequestURI().getRawQuery());

		try {
			x = Integer.parseInt(queryParams.getOrDefault("x", "0"));
			y = Integer.parseInt(queryParams.getOrDefault("y", "0"));
			z = Integer.parseInt(queryParams.getOrDefault("z", "0"));

			dx = Integer.parseInt(queryParams.getOrDefault("dx", "1"));
			dy = Integer.parseInt(queryParams.getOrDefault("dy", "1"));
			dz = Integer.parseInt(queryParams.getOrDefault("dz", "1"));

			mirror = queryParams.getOrDefault("mirror", "");

			rotate = Integer.parseInt(queryParams.getOrDefault("rotate", "0")) % 4;

			pivotX = Integer.parseInt(queryParams.getOrDefault("pivotx", "0"));
			pivotZ = Integer.parseInt(queryParams.getOrDefault("pivotz", "0"));
			includeEntities = Boolean.parseBoolean(queryParams.getOrDefault("entities", "false"));

			doBlockUpdates = Boolean.parseBoolean(queryParams.getOrDefault("doBlockUpdates", "true"));

			spawnDrops = Boolean.parseBoolean(queryParams.getOrDefault("spawnDrops", "false"));

			customFlags = Integer.parseInt(queryParams.getOrDefault("customFlags", "-1"), 2);

			dimension = queryParams.getOrDefault("dimension", null);
		} catch (NumberFormatException e) {
			throw new HandlerBase.HttpException("Could not parse query parameter: " + e.getMessage(), 400);
		}

		// Check if clients wants a response in plain-text or JSON format.
		// POST: If not defined, return response in a plain-text format.
		// GET: If not defined, return response in a binary format.
		Headers requestHeaders = httpExchange.getRequestHeaders();
		String acceptHeader = getHeader(requestHeaders, "Accept", "*/*");
		boolean returnPlainText = acceptHeader.equals("text/plain");

		switch (httpExchange.getRequestMethod().toLowerCase()) {
			case "post" -> {
				// Check if there is a header present stating that the request body is compressed with GZIP.
				// Any structure file generated by Minecraft itself using the Structure Block
				// (http://minecraft.wiki/w/Structure_Block) as well as the built-in Structures are
				// stored in this compressed format.
				String contentEncodingHeader = getHeader(requestHeaders, "Content-Encoding", "*");
				boolean inputShouldBeCompressed = contentEncodingHeader.equals("gzip");
				postStructureHandler(httpExchange, inputShouldBeCompressed);
			}
			case "get" -> {
				// If "Accept-Encoding" header is set to "gzip" and the client expects a binary format,
				// (both default) compress the result using GZIP before sending out the response.
				String acceptEncodingHeader = getHeader(requestHeaders, "Accept-Encoding", "gzip");
				boolean returnCompressed = acceptEncodingHeader.contains("gzip");
				getStructureHandler(httpExchange, returnPlainText, returnCompressed);
			}
			default -> throw new HttpException("Method not allowed. Only POST and GET requests are supported.", 405);
		}
	}

	private void postStructureHandler(HttpExchange httpExchange, boolean parseRequestAsGzip) throws IOException {
		JsonObject responseValue;

		CompoundTag structureCompound;
		ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
		try {
			httpExchange.getRequestBody().transferTo(outputStream);
			if (outputStream.size() == 0) {
				throw new HttpException("Request body is empty", 400);
			}
		} catch (IOException e1) {
			throw new HttpException("Could not process request body: " + e1.getMessage(), 400);
		}
		try {
			// Read request body into NBT data compound that can be placed in the world.
			structureCompound = NbtIo.readCompressed(new ByteArrayInputStream(outputStream.toByteArray()));
		} catch (IOException e2) {
			// If header states the content should be compressed but it isn't, throw an error. Otherwise, try
			// reading the content again, assuming it is not compressed.
			if (parseRequestAsGzip) {
				throw new HttpException("Could not process request body: " + e2.getMessage(), 400);
			}
			try {
				DataInputStream dataInputStream = new DataInputStream(new ByteArrayInputStream(outputStream.toByteArray()));
				structureCompound = NbtIo.read(dataInputStream);
			} catch (IOException e3) {
				throw new HttpException("Could not process request body: " + e3.getMessage(), 400);
			}
		}

		// Prepare transformation settings for the structure.
		StructurePlaceSettings structurePlaceSettings = new StructurePlaceSettings();
		switch (mirror) {
			case "x" -> structurePlaceSettings.setMirror(Mirror.FRONT_BACK);
			case "z" -> structurePlaceSettings.setMirror(Mirror.LEFT_RIGHT);
		}
		switch (rotate) {
			case 1 -> structurePlaceSettings.setRotation(Rotation.CLOCKWISE_90);
			case 2 -> structurePlaceSettings.setRotation(Rotation.CLOCKWISE_180);
			case 3 -> structurePlaceSettings.setRotation(Rotation.COUNTERCLOCKWISE_90);
		}
		structurePlaceSettings.setRotationPivot(new BlockPos(pivotX, 0, pivotZ));
		structurePlaceSettings.setIgnoreEntities(!includeEntities);

		ServerLevel serverLevel = getServerLevel(dimension);

		try {

			StructureTemplate structureTemplate = serverLevel.getStructureManager().readStructure(structureCompound);

			BlockPos origin = new BlockPos(x, y, z);
			int blockPlacementFlags = customFlags >= 0 ? customFlags : BlocksHandler.getBlockFlags(doBlockUpdates, spawnDrops);

			boolean hasPlaced = structureTemplate.placeInWorld(
				serverLevel,
				origin,
				origin,
				structurePlaceSettings,
				serverLevel.getRandom(),
				blockPlacementFlags
			);
			if (hasPlaced) {
				// After placement, go through all blocks listed in the structureCompound and place the corresponding block entity data
				// stored at key "nbt" using the same placement settings as the structure itself.
				ListTag blockList = structureCompound.getList("blocks", Tag.TAG_COMPOUND);
				for (int i = 0; i < blockList.size(); i++) {
					CompoundTag tag = blockList.getCompound(i);
					if (tag.contains("nbt")) {
						ListTag posTag = tag.getList("pos", Tag.TAG_INT);
						BlockPos blockPosInStructure = new BlockPos(posTag.getInt(0), posTag.getInt(1), posTag.getInt(2));
						BlockPos transformedGlobalBlockPos = origin.offset(StructureTemplate.calculateRelativePosition(structurePlaceSettings, blockPosInStructure));

						BlockEntity existingBlockEntity = serverLevel.getExistingBlockEntity(transformedGlobalBlockPos);
						if (existingBlockEntity != null) {
							existingBlockEntity.deserializeNBT(tag.getCompound("nbt"));
							serverLevel.markAndNotifyBlock(
								transformedGlobalBlockPos, serverLevel.getChunkAt(transformedGlobalBlockPos),
								serverLevel.getBlockState(transformedGlobalBlockPos), existingBlockEntity.getBlockState(),
								blockPlacementFlags, Block.UPDATE_LIMIT
							);
						}
					}
				}
				responseValue = instructionStatus(true);
			} else {
				responseValue = instructionStatus(false);
			}
		} catch (Exception exception) {
			throw new HttpException("Could not place structure: " + exception.getMessage(), 400);
		}

		Headers responseHeaders = httpExchange.getResponseHeaders();
		setDefaultResponseHeaders(responseHeaders);

		resolveRequest(httpExchange, responseValue.toString());
	}

	private void getStructureHandler(HttpExchange httpExchange, boolean returnPlainText, boolean returnCompressed) throws IOException {
		// Calculate boundaries of area of blocks to gather information on.
		int xOffset = x + dx;
		int xMin = Math.min(x, xOffset);

		int yOffset = y + dy;
		int yMin = Math.min(y, yOffset);

		int zOffset = z + dz;
		int zMin = Math.min(z, zOffset);

		// Create StructureTemplate using blocks within the given area of the world.
		StructureTemplate structureTemplate = new StructureTemplate();
		ServerLevel serverLevel = getServerLevel(dimension);
		BlockPos origin = new BlockPos(xMin, yMin, zMin);
		Vec3i size = new Vec3i(Math.abs(dx), Math.abs(dy), Math.abs(dz));
		structureTemplate.fillFromWorld(
			serverLevel,
			origin,
			size,
			includeEntities,
			null
		);

		CompoundTag newStructureCompoundTag = structureTemplate.save(new CompoundTag());

		// Gather all existing block entity data for that same area of the world and append it to the
		// exported CompoundTag from the structure template.
		ListTag blockList = newStructureCompoundTag.getList("blocks", Tag.TAG_COMPOUND);
		for (int i = 0; i < blockList.size(); i++) {
			CompoundTag tag = blockList.getCompound(i);
			if (tag.contains("nbt") || !tag.contains("pos")) {
				continue;
			}
			ListTag posTag = tag.getList("pos", Tag.TAG_INT);
			BlockPos blockPosInStructure = new BlockPos(posTag.getInt(0), posTag.getInt(1), posTag.getInt(2));
			BlockPos blockPosInWorld = origin.offset(blockPosInStructure);

			BlockEntity existingBlockEntity = serverLevel.getExistingBlockEntity(blockPosInWorld);
			if (existingBlockEntity != null) {
				CompoundTag blockEntityCompoundTag = existingBlockEntity.saveWithoutMetadata();
				tag.put("nbt", blockEntityCompoundTag);
			}
		}

		Headers responseHeaders = httpExchange.getResponseHeaders();
		setDefaultResponseHeaders(responseHeaders);

		if (returnPlainText) {
			String responseString = newStructureCompoundTag.toString();

			setResponseHeadersContentTypePlain(responseHeaders);
			resolveRequest(httpExchange, responseString);
			return;
		}

		setResponseHeadersContentTypeBinary(responseHeaders, returnCompressed);

		ByteArrayOutputStream boas = new ByteArrayOutputStream();
		if (returnCompressed) {
			GZIPOutputStream dos = new GZIPOutputStream(boas);
			NbtIo.writeCompressed(newStructureCompoundTag, dos);
			dos.flush();
			byte[] responseBytes = boas.toByteArray();

			resolveRequest(httpExchange, responseBytes);
			return;
		}
		DataOutputStream dos = new DataOutputStream(boas);
		NbtIo.write(newStructureCompoundTag, dos);
		dos.flush();
		byte[] responseBytes = boas.toByteArray();

		resolveRequest(httpExchange, responseBytes);
	}
}
