/*
 * This file is part of BlueMap, licensed under the MIT License (MIT).
 *
 * Copyright (c) Blue (Lukas Rieger) <https://bluecolored.de>
 * Copyright (c) contributors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package de.bluecolored.bluemap.core.resourcepack;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import javax.imageio.ImageIO;

import com.flowpowered.math.vector.Vector4f;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonStreamParser;

import de.bluecolored.bluemap.core.logger.Logger;
import de.bluecolored.bluemap.core.resourcepack.fileaccess.FileAccess;

/**
 * A {@link TextureGallery} is managing {@link Texture}s and their id's and path's.<br>
 * I can also load and generate the texture.json file, or load new {@link Texture}s from a {@link FileAccess}. 
 */
public class TextureGallery {

	private static final String EMPTY_BASE64 = "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAABAAAAAQCAQAAAC1+jfqAAAAEUlEQVR42mNkIAAYRxWMJAUAE5gAEdz4t9QAAAAASUVORK5CYII=";
	
	private Map<String, Texture> textureMap;
	private List<Texture> textureList;
	
	public TextureGallery() {
		textureMap = new HashMap<>();
		textureList = new ArrayList<>();
	}
	
	/**
	 * Returns a {@link Texture} by its id, there can always be only one texture per id in a gallery.
	 * @param id The texture id
	 * @return The {@link Texture}
	 */
	public Texture get(int id) {
		return textureList.get(id);
	}
	
	/**
	 * Returns a {@link Texture} by its path, there can always be only one texture per path in a gallery.
	 * @param path The texture-path
	 * @return The {@link Texture}
	 */
	public Texture get(String path) {
		Texture texture = textureMap.get(path);
		if (texture == null) throw new NoSuchElementException("There is no texture with the path " + path + " in this gallery!");
		return texture;
	}
	
	/**
	 * The count of {@link Texture}s managed by this gallery
	 * @return The count of textures
	 */
	public int size() {
		return textureList.size();
	}
	
	/**
	 * Generates a texture.json file with all the {@link Texture}s in this gallery
	 * @param file The file to save the json in
	 * @throws IOException If an IOException occurs while writing
	 */
	public void saveTextureFile(File file) throws IOException {
		
		JsonArray textures = new JsonArray();
		for (int i = 0; i < textureList.size(); i++) {
			Texture texture = textureList.get(i);
			
			JsonObject textureNode = new JsonObject();
			textureNode.addProperty("id", texture.getPath());
			textureNode.addProperty("texture", texture.getTexture());
			textureNode.addProperty("transparent", texture.isHalfTransparent());
			
			Vector4f color = texture.getColor();
			JsonArray colorNode = new JsonArray();
			colorNode.add(color.getX());
			colorNode.add(color.getY());
			colorNode.add(color.getZ());
			colorNode.add(color.getW());
			
			textureNode.add("color", colorNode);
			
			textures.add(textureNode);
		}
		
		JsonObject root = new JsonObject();
		root.add("textures", textures);
		
		Gson gson = new GsonBuilder()
				.setPrettyPrinting()
				.create();
		String json = gson.toJson(root);
		
		file.delete();
		file.getParentFile().mkdirs();
		file.createNewFile();
		
		try (FileWriter fileWriter = new FileWriter(file)) {
			fileWriter.append(json);
			fileWriter.flush();
		}
		
	}
	
	/**
	 * Loads all the {@link Texture}s from the provided texture.json file, removes any existing {@link Texture}s from this gallery.
	 * @param file The texture.json file.
	 * @throws IOException If an IOException occurs while reading the file.
	 * @throws ParseResourceException If the whole file can not be read. Errors with single textures are logged and ignored. 
	 */
	public synchronized void loadTextureFile(File file) throws IOException, ParseResourceException {
		textureList.clear();
		textureMap.clear();
		
		try (FileReader fileReader = new FileReader(file)){
			JsonStreamParser jsonFile = new JsonStreamParser(fileReader);
			JsonArray textures = jsonFile.next().getAsJsonObject().getAsJsonArray("textures");
			int size = textures.size();
			for (int i = 0; i < size; i++) {
				while (i >= textureList.size()) { //prepopulate with placeholder so we don't get an IndexOutOfBounds below
					textureList.add(new Texture(textureList.size(), "empty", Vector4f.ZERO, false, EMPTY_BASE64));
				}
				
				try {
					JsonObject texture = textures.get(i).getAsJsonObject();
					String path = texture.get("id").getAsString();
					boolean transparent = texture.get("transparent").getAsBoolean();
					Vector4f color = readVector4f(texture.get("color").getAsJsonArray());
					textureList.set(i, new Texture(i, path, color, transparent, EMPTY_BASE64));
				} catch (Exception ex) {
					Logger.global.logWarning("Failed to load texture with id " + i + " from texture file " + file + "!");
				}
			}
		} catch (IOException ex) {
			throw ex;
		} catch (Exception ex) {
			throw new ParseResourceException("Invalid texture file format!", ex);
		} finally {
			regenerateMap();
		}
	}
	
	/**
	 * Loads a {@link Texture} from the {@link FileAccess} and the given path and returns it.<br>
	 * If there is already a {@link Texture} with this path in this Gallery it replaces the {@link Texture} with the new one 
	 * and the new one will have the same id as the old one.<br>
	 * Otherwise the {@link Texture} will be added to the end of this gallery with the next available id.
	 * @param fileAccess The {@link FileAccess} to load the image from.
	 * @param path The path of the image on the {@link FileAccess}
	 * @return The loaded {@link Texture}
	 * @throws FileNotFoundException If there is no image in that FileAccess on that path
	 * @throws IOException If an IOException occurred while loading the file
	 */
	public synchronized Texture loadTexture(FileAccess fileAccess, String path) throws FileNotFoundException, IOException {
		try (InputStream input = fileAccess.readFile(path)) {
			BufferedImage image = ImageIO.read(input);
			if (image == null) throw new IOException("Failed to read image: " + path);
			
			//crop off animation frames
			if (image.getHeight() > image.getWidth()){
				BufferedImage cropped = new BufferedImage(image.getWidth(), image.getWidth(), BufferedImage.TYPE_INT_ARGB);
				Graphics2D g = cropped.createGraphics();
				g.drawImage(image, 0, 0, null);
				image = cropped;
			}
			
			//check halfTransparency
			boolean halfTransparent = checkHalfTransparent(image);
			
			//calculate color
			Vector4f color = calculateColor(image);
			
			//write to Base64
			ByteArrayOutputStream os = new ByteArrayOutputStream();
			ImageIO.write(image, "png", os);
			String base64 = "data:image/png;base64," + Base64.getEncoder().encodeToString(os.toByteArray());
			
			//replace if texture with this path already exists
			Texture texture = textureMap.get(path);
			if (texture != null) {
				texture = new Texture(texture.getId(), path, color, halfTransparent, base64);
				textureMap.put(path, texture);
				textureList.set(texture.getId(), texture);
			} else {
				texture = new Texture(textureList.size(), path, color, halfTransparent, base64);
				textureMap.put(path, texture);
				textureList.add(texture);
			}
			
			return texture;
		}
	}
	
	/**
	 * Tries to reload all {@link Texture}s from the given {@link FileAccess}<br>
	 * <br>
	 * Exceptions are being logged and ignored. 
	 * @param fileAccess The {@link FileAccess} to load the {@link Texture}s from
	 */
	public synchronized void reloadAllTextures(FileAccess fileAccess) {
		for (Texture texture : textureList.toArray(new Texture[textureList.size()])) {
			try {
				loadTexture(fileAccess, texture.getPath());
			} catch (IOException e) {
				Logger.global.logWarning("Failed to reload texture: " + texture.getPath());
				Logger.global.noFloodWarning("This happens if the resource-packs have changed, but you have not deleted your generated maps. This might result in broken map-models!");
			}
		}
	}

	private synchronized void regenerateMap() {
		textureMap.clear();
		for (int i = 0; i < textureList.size(); i++) {
			Texture texture = textureList.get(i);
			textureMap.put(texture.getPath(), texture);
		}
	}

	private Vector4f readVector4f(JsonArray jsonArray) throws ParseResourceException {
		if (jsonArray.size() < 4) throw new ParseResourceException("Failed to load Vector4: Not enough values in list-node!");
		
		float r = jsonArray.get(0).getAsFloat();
		float g = jsonArray.get(1).getAsFloat();
		float b = jsonArray.get(2).getAsFloat();
		float a = jsonArray.get(3).getAsFloat();
		
		return new Vector4f(r, g, b, a);
	}
	
	private boolean checkHalfTransparent(BufferedImage image){
		for (int x = 0; x < image.getWidth(); x++){
			for (int y = 0; y < image.getHeight(); y++){
				int pixel = image.getRGB(x, y);
				int alpha = (pixel >> 24) & 0xff;
				if (alpha > 0x00 && alpha < 0xff){
					return true;
				}
			}
		}
		
		return false;
	}
	
	private Vector4f calculateColor(BufferedImage image){
		double alpha = 0d, red = 0d, green = 0d, blue = 0d;
		int count = 0;
		
		for (int x = 0; x < image.getWidth(); x++){
			for (int y = 0; y < image.getHeight(); y++){
				int pixel = image.getRGB(x, y);
				double pixelAlpha = (double)((pixel >> 24) & 0xff) / (double) 0xff;
		        double pixelRed = (double)((pixel >> 16) & 0xff) / (double) 0xff;
		        double pixelGreen = (double)((pixel >> 8) & 0xff) / (double) 0xff;
		        double pixelBlue = (double)((pixel >> 0) & 0xff) / (double) 0xff;
		        
		        count++;
		        alpha += pixelAlpha;
		        red += pixelRed * pixelAlpha;
		        green += pixelGreen * pixelAlpha;
		        blue += pixelBlue * pixelAlpha;
			}
		}
		
		if (count == 0 || alpha == 0) return Vector4f.ZERO;
		
		red /= alpha;
		green /= alpha;
		blue /= alpha;
		alpha /= count;
		
		return new Vector4f(red, green, blue, alpha);
	}
	
}
