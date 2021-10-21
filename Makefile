CC = kotlinc
RM = del
SRC = src/main/kotlin/zx0/

all: zx0

zx0: $(SRC)Main.kt $(SRC)Block.kt $(SRC)Optimizer.kt $(SRC)Compressor.kt $(SRC)Decompressor.kt
	$(CC) $(SRC)Main.kt $(SRC)Block.kt $(SRC)Optimizer.kt $(SRC)Compressor.kt $(SRC)Decompressor.kt -include-runtime -d zx0.jar

clean:
	$(RM) zx0.jar
