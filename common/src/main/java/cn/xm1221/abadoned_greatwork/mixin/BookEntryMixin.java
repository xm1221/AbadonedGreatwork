package cn.xm1221.abadoned_greatwork.mixin;

import cn.xm1221.abadoned_greatwork.client.notes.patchouli.UserNoteBookInjector;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import vazkii.patchouli.client.book.BookContentsBuilder;
import vazkii.patchouli.client.book.BookEntry;
import vazkii.patchouli.client.book.BookPage;
import vazkii.patchouli.common.book.Book;

import java.util.List;

@Mixin(value = BookEntry.class, remap = false)
public abstract class BookEntryMixin {
    @Shadow
    @Final
    private ResourceLocation id;

    @Shadow
    @Final
    private Book book;

    @Shadow
    @Final
    private BookPage[] pages;

    @Shadow
    @Final
    private List<BookPage> realPages;

    @Shadow
    private boolean built;

    @Inject(method = "build", at = @At("HEAD"), cancellable = true)
    private void abw$buildUserNoteSafely(Level level, BookContentsBuilder builder, CallbackInfo ci) {
        if (!UserNoteBookInjector.isUserNote(this.id)) {
            return;
        }
        UserNoteBookInjector.buildSafely(
                level,
                builder,
                (BookEntry) (Object) this,
                this.id,
                this.book,
                this.pages,
                this.realPages
        );
        this.built = true;
        ci.cancel();
    }
}
